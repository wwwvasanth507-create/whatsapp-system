-- CampusChat Supabase Migration: 07 - Encrypted Message Transport Queue
-- Purpose: Per-device encrypted Signal ciphertext transport queue for asynchronous Double Ratchet messaging.
-- Security Rule: ONLY encrypted ciphertext envelopes are stored. NO PLAINTEXT OR KEYS EVER STORED.

--------------------------------------------------------------------------------
-- 1. DROP LEGACY TABLES IF THEY EXIST TO PREVENT CONFLICTS
--------------------------------------------------------------------------------
DROP TABLE IF EXISTS public.message_deliveries CASCADE;
DROP TABLE IF EXISTS public.messages CASCADE;

--------------------------------------------------------------------------------
-- 2. ENCRYPTED MESSAGES QUEUE TABLE
--------------------------------------------------------------------------------
CREATE TABLE public.messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    sender_user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    sender_device_id UUID NOT NULL,
    recipient_user_id UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    recipient_device_id UUID NOT NULL,
    message_type SMALLINT NOT NULL, -- 2=WHISPER_TYPE, 3=PREKEY_TYPE
    ciphertext TEXT NOT NULL, -- Base64 serialized EncryptedMessageEnvelope ciphertext
    server_created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL DEFAULT (now() + INTERVAL '30 days'),
    delivery_status TEXT NOT NULL DEFAULT 'pending' CHECK (delivery_status IN ('pending', 'delivered', 'failed', 'expired')),
    attempt_count INTEGER NOT NULL DEFAULT 0,
    delivered_at TIMESTAMPTZ NULL,
    CONSTRAINT fk_messages_sender_device FOREIGN KEY (sender_device_id, sender_user_id)
        REFERENCES public.devices(id, user_id) ON DELETE CASCADE,
    CONSTRAINT fk_messages_recipient_device FOREIGN KEY (recipient_device_id, recipient_user_id)
        REFERENCES public.devices(id, user_id) ON DELETE CASCADE
);

COMMENT ON TABLE public.messages IS 'Per-device encrypted Double Ratchet ciphertext transport queue. Stores ciphertext only.';
COMMENT ON COLUMN public.messages.ciphertext IS 'Base64 serialized Signal protocol ciphertext. NEVER store plaintext!';

--------------------------------------------------------------------------------
-- 3. INDEXES FOR PERFORMANCE
--------------------------------------------------------------------------------
CREATE INDEX idx_messages_recipient_pending ON public.messages(recipient_user_id, recipient_device_id, delivery_status)
    WHERE delivery_status = 'pending';
CREATE INDEX idx_messages_sender ON public.messages(sender_user_id, sender_device_id);
CREATE INDEX idx_messages_expiration ON public.messages(expires_at)
    WHERE delivery_status = 'pending';

--------------------------------------------------------------------------------
-- 4. ROW LEVEL SECURITY (RLS)
--------------------------------------------------------------------------------
ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;

-- Policy 1: Sender can insert messages only when sender_user_id matches authenticated user
CREATE POLICY messages_insert_sender ON public.messages
    FOR INSERT
    TO authenticated
    WITH CHECK (
        sender_user_id = auth.uid()
        AND EXISTS (
            SELECT 1 FROM public.devices d
            WHERE d.id = sender_device_id
              AND d.user_id = auth.uid()
              AND d.status = 'ACTIVE'
        )
        AND EXISTS (
            SELECT 1 FROM public.devices d
            WHERE d.id = recipient_device_id
              AND d.user_id = recipient_user_id
              AND d.status = 'ACTIVE'
        )
    );

-- Policy 2: Recipient can select pending messages addressed to recipient_user_id
CREATE POLICY messages_select_recipient ON public.messages
    FOR SELECT
    TO authenticated
    USING (
        recipient_user_id = auth.uid()
    );

-- Policy 3: Block direct client UPDATE for normal authenticated users (must use RPC)
CREATE POLICY messages_update_block ON public.messages
    FOR UPDATE
    TO authenticated
    USING (false);

-- Policy 4: Block direct client DELETE for normal authenticated users (must use RPC)
CREATE POLICY messages_delete_block ON public.messages
    FOR DELETE
    TO authenticated
    USING (false);

--------------------------------------------------------------------------------
-- 5. HARDENED SECURITY DEFINER RPCs
--------------------------------------------------------------------------------

-- RPC 1: Enqueue Encrypted Message
CREATE OR REPLACE FUNCTION public.enqueue_encrypted_message(
    p_sender_device_id UUID,
    p_recipient_user_id UUID,
    p_recipient_device_id UUID,
    p_message_type SMALLINT,
    p_ciphertext TEXT,
    p_ttl_seconds INTEGER DEFAULT 2592000 -- 30 days default TTL
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    v_caller_id UUID;
    v_message_id UUID;
    v_expires_at TIMESTAMPTZ;
BEGIN
    v_caller_id := auth.uid();
    IF v_caller_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    -- Validate payload size limit (~30,000 chars Base64 ciphertext)
    IF length(p_ciphertext) > 30000 THEN
        RAISE EXCEPTION 'Ciphertext payload exceeds maximum permitted size';
    END IF;

    -- Validate sender device ownership
    IF NOT EXISTS (
        SELECT 1 FROM public.devices
        WHERE id = p_sender_device_id
          AND user_id = v_caller_id
          AND status = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'Invalid or unowned sender device';
    END IF;

    -- Validate recipient device existence
    IF NOT EXISTS (
        SELECT 1 FROM public.devices
        WHERE id = p_recipient_device_id
          AND user_id = p_recipient_user_id
          AND status = 'ACTIVE'
    ) THEN
        RAISE EXCEPTION 'Invalid recipient device';
    END IF;

    v_expires_at := now() + (p_ttl_seconds || ' seconds')::INTERVAL;

    INSERT INTO public.messages (
        sender_user_id,
        sender_device_id,
        recipient_user_id,
        recipient_device_id,
        message_type,
        ciphertext,
        expires_at,
        delivery_status
    ) VALUES (
        v_caller_id,
        p_sender_device_id,
        p_recipient_user_id,
        p_recipient_device_id,
        p_message_type,
        p_ciphertext,
        v_expires_at,
        'pending'
    ) RETURNING id INTO v_message_id;

    RETURN v_message_id;
END;
$$;

-- RPC 2: Fetch Pending Messages for Recipient Device
CREATE OR REPLACE FUNCTION public.fetch_pending_messages(
    p_recipient_device_id UUID
)
RETURNS TABLE (
    id UUID,
    sender_user_id UUID,
    sender_device_id UUID,
    recipient_user_id UUID,
    recipient_device_id UUID,
    message_type SMALLINT,
    ciphertext TEXT,
    server_created_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    v_caller_id UUID;
BEGIN
    v_caller_id := auth.uid();
    IF v_caller_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    RETURN QUERY
    SELECT 
        m.id,
        m.sender_user_id,
        m.sender_device_id,
        m.recipient_user_id,
        m.recipient_device_id,
        m.message_type,
        m.ciphertext,
        m.server_created_at,
        m.expires_at
    FROM public.messages m
    WHERE m.recipient_user_id = v_caller_id
      AND m.recipient_device_id = p_recipient_device_id
      AND m.delivery_status = 'pending'
      AND m.expires_at > now()
    ORDER BY m.server_created_at ASC;
END;
$$;

-- RPC 3: Acknowledge and Delete Delivered Pending Message
CREATE OR REPLACE FUNCTION public.acknowledge_message_delivery(
    p_message_id UUID
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    v_caller_id UUID;
    v_rows_affected INTEGER;
BEGIN
    v_caller_id := auth.uid();
    IF v_caller_id IS NULL THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    -- Delete the pending message row upon successful local decryption & processing
    DELETE FROM public.messages
    WHERE id = p_message_id
      AND recipient_user_id = v_caller_id;

    GET DIAGNOSTICS v_rows_affected = ROW_COUNT;
    RETURN v_rows_affected > 0;
END;
$$;

-- RPC 4: Server-Side Cleanup of Expired Messages
CREATE OR REPLACE FUNCTION public.cleanup_expired_messages()
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    v_deleted_count INTEGER;
BEGIN
    DELETE FROM public.messages
    WHERE expires_at < now();

    GET DIAGNOSTICS v_deleted_count = ROW_COUNT;
    RETURN v_deleted_count;
END;
$$;

--------------------------------------------------------------------------------
-- 6. GRANT AUTHORIZATION PRIVILEGES
--------------------------------------------------------------------------------
REVOKE EXECUTE ON FUNCTION public.enqueue_encrypted_message FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.fetch_pending_messages FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.acknowledge_message_delivery FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.cleanup_expired_messages FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.enqueue_encrypted_message TO authenticated;
GRANT EXECUTE ON FUNCTION public.fetch_pending_messages TO authenticated;
GRANT EXECUTE ON FUNCTION public.acknowledge_message_delivery TO authenticated;
GRANT EXECUTE ON FUNCTION public.cleanup_expired_messages TO service_role;
