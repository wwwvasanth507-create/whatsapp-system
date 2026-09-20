-- CampusChat Supabase Migration: 06 - Signal Protocol Public Key Foundation (Hardened)
-- Purpose: Server-side public key directory for Signal X3DH session establishment.
-- Security Rule: Strictly stores PUBLIC cryptographic material only. NO PRIVATE KEYS EVER STORED.

--------------------------------------------------------------------------------
-- 1. DEVICE SCHEMA EXTENSION (STATUS & REGISTRATION_ID & COMPOSITE CONSTRAINT)
--------------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = 'public' AND table_name = 'devices' AND column_name = 'status'
    ) THEN
        ALTER TABLE public.devices 
        ADD COLUMN status TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'REVOKED', 'DELETED'));
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = 'public' AND table_name = 'devices' AND column_name = 'registration_id'
    ) THEN
        ALTER TABLE public.devices 
        ADD COLUMN registration_id INTEGER;
    END IF;
END $$;

-- Enforce composite constraint (id, user_id) on public.devices for foreign key integrity
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'unique_device_user'
    ) THEN
        ALTER TABLE public.devices ADD CONSTRAINT unique_device_user UNIQUE (id, user_id);
    END IF;
END $$;

--------------------------------------------------------------------------------
-- 2. DEVICE IDENTITY KEYS TABLE
--------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.device_identity_keys (
    device_id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    identity_public_key TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_device_identity_user FOREIGN KEY (device_id, user_id) 
        REFERENCES public.devices(id, user_id) ON DELETE CASCADE
);

COMMENT ON TABLE public.device_identity_keys IS 'Long-term Signal Public Identity Keys (IK) per device with strict composite owner validation.';

--------------------------------------------------------------------------------
-- 3. DEVICE SIGNED PREKEYS TABLE
--------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.device_signed_prekeys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id UUID NOT NULL,
    user_id UUID NOT NULL,
    key_id INTEGER NOT NULL,
    public_key TEXT NOT NULL,
    signature TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_device_signed_prekey_user FOREIGN KEY (device_id, user_id) 
        REFERENCES public.devices(id, user_id) ON DELETE CASCADE,
    CONSTRAINT unique_device_signed_prekey UNIQUE (device_id, key_id)
);

COMMENT ON TABLE public.device_signed_prekeys IS 'Signal Signed PreKeys (SPK) with Ed25519/Curve25519 signatures per device.';

--------------------------------------------------------------------------------
-- 4. DEVICE ONE-TIME PREKEYS TABLE
--------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.device_one_time_prekeys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id UUID NOT NULL,
    user_id UUID NOT NULL,
    key_id INTEGER NOT NULL,
    public_key TEXT NOT NULL,
    is_consumed BOOLEAN NOT NULL DEFAULT false,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_device_one_time_prekey_user FOREIGN KEY (device_id, user_id) 
        REFERENCES public.devices(id, user_id) ON DELETE CASCADE,
    CONSTRAINT unique_device_one_time_prekey UNIQUE (device_id, key_id)
);

COMMENT ON TABLE public.device_one_time_prekeys IS 'Pool of single-use Signal One-Time PreKeys (OPK) per device.';

--------------------------------------------------------------------------------
-- 5. INDEXES
--------------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_device_identity_keys_user ON public.device_identity_keys(user_id);
CREATE INDEX IF NOT EXISTS idx_device_signed_prekeys_lookup ON public.device_signed_prekeys(device_id, is_active) WHERE is_active = true;
CREATE INDEX IF NOT EXISTS idx_device_one_time_prekeys_available ON public.device_one_time_prekeys(device_id, is_consumed) WHERE is_consumed = false;

--------------------------------------------------------------------------------
-- 6. HARDENED ROW LEVEL SECURITY (RLS)
--------------------------------------------------------------------------------
ALTER TABLE public.device_identity_keys ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.device_signed_prekeys ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.device_one_time_prekeys ENABLE ROW LEVEL SECURITY;

-- A. Identity Keys Policies
DROP POLICY IF EXISTS "authenticated_select_active_device_identity_keys" ON public.device_identity_keys;
CREATE POLICY "authenticated_select_active_device_identity_keys"
    ON public.device_identity_keys FOR SELECT TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.devices d
            WHERE d.id = device_identity_keys.device_id AND d.status = 'ACTIVE'
        )
    );

DROP POLICY IF EXISTS "owner_manage_device_identity_keys" ON public.device_identity_keys;
CREATE POLICY "owner_manage_device_identity_keys"
    ON public.device_identity_keys FOR ALL TO authenticated
    USING (
        user_id = auth.uid()
        AND EXISTS (
            SELECT 1 FROM public.devices d
            WHERE d.id = device_identity_keys.device_id AND d.user_id = auth.uid() AND d.status = 'ACTIVE'
        )
    )
    WITH CHECK (
        user_id = auth.uid()
        AND EXISTS (
            SELECT 1 FROM public.devices d
            WHERE d.id = device_identity_keys.device_id AND d.user_id = auth.uid() AND d.status = 'ACTIVE'
        )
    );

-- B. Signed PreKeys Policies
DROP POLICY IF EXISTS "authenticated_select_active_device_signed_prekeys" ON public.device_signed_prekeys;
CREATE POLICY "authenticated_select_active_device_signed_prekeys"
    ON public.device_signed_prekeys FOR SELECT TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.devices d
            WHERE d.id = device_signed_prekeys.device_id AND d.status = 'ACTIVE'
        )
    );

DROP POLICY IF EXISTS "owner_manage_device_signed_prekeys" ON public.device_signed_prekeys;
CREATE POLICY "owner_manage_device_signed_prekeys"
    ON public.device_signed_prekeys FOR ALL TO authenticated
    USING (
        user_id = auth.uid()
        AND EXISTS (
            SELECT 1 FROM public.devices d
            WHERE d.id = device_signed_prekeys.device_id AND d.user_id = auth.uid() AND d.status = 'ACTIVE'
        )
    )
    WITH CHECK (
        user_id = auth.uid()
        AND EXISTS (
            SELECT 1 FROM public.devices d
            WHERE d.id = device_signed_prekeys.device_id AND d.user_id = auth.uid() AND d.status = 'ACTIVE'
        )
    );

-- C. One-Time PreKeys Policies (Hardened - Direct UPDATE Blocked for Clients)
DROP POLICY IF EXISTS "authenticated_select_active_device_one_time_prekeys" ON public.device_one_time_prekeys;
CREATE POLICY "authenticated_select_active_device_one_time_prekeys"
    ON public.device_one_time_prekeys FOR SELECT TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.devices d
            WHERE d.id = device_one_time_prekeys.device_id AND d.status = 'ACTIVE'
        )
    );

DROP POLICY IF EXISTS "owner_manage_device_one_time_prekeys" ON public.device_one_time_prekeys;
DROP POLICY IF EXISTS "owner_insert_device_one_time_prekeys" ON public.device_one_time_prekeys;
CREATE POLICY "owner_insert_device_one_time_prekeys"
    ON public.device_one_time_prekeys FOR INSERT TO authenticated
    WITH CHECK (
        user_id = auth.uid()
        AND is_consumed = false
        AND EXISTS (
            SELECT 1 FROM public.devices d
            WHERE d.id = device_one_time_prekeys.device_id AND d.user_id = auth.uid() AND d.status = 'ACTIVE'
        )
    );

DROP POLICY IF EXISTS "owner_delete_device_one_time_prekeys" ON public.device_one_time_prekeys;
CREATE POLICY "owner_delete_device_one_time_prekeys"
    ON public.device_one_time_prekeys FOR DELETE TO authenticated
    USING (
        user_id = auth.uid()
        AND EXISTS (
            SELECT 1 FROM public.devices d
            WHERE d.id = device_one_time_prekeys.device_id AND d.user_id = auth.uid() AND d.status = 'ACTIVE'
        )
    );

-- Note: No UPDATE policy is granted to authenticated clients for device_one_time_prekeys.
-- OPK consumption is strictly performed by the SECURITY DEFINER function claim_prekey_bundle().

--------------------------------------------------------------------------------
-- 7. HARDENED ATOMIC PREKEY CLAIM RPC FUNCTION
--------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.claim_prekey_bundle(p_recipient_device_id UUID)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_device RECORD;
    v_identity RECORD;
    v_signed_prekey RECORD;
    v_opk RECORD;
    v_result JSONB;
BEGIN
    -- Security Check: Caller must be authenticated
    IF auth.role() IS NULL OR auth.role() != 'authenticated' THEN
        RAISE EXCEPTION 'Authentication required to claim prekey bundle';
    END IF;

    -- Verify target device exists and is ACTIVE
    SELECT id, user_id, registration_id, status
    INTO v_device
    FROM public.devices
    WHERE id = p_recipient_device_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Target device does not exist';
    END IF;

    IF v_device.status != 'ACTIVE' THEN
        RAISE EXCEPTION 'Target device is not active';
    END IF;

    -- Fetch Identity Key
    SELECT identity_public_key
    INTO v_identity
    FROM public.device_identity_keys
    WHERE device_id = p_recipient_device_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Target device identity key not registered';
    END IF;

    -- Fetch Active Signed PreKey
    SELECT key_id, public_key, signature
    INTO v_signed_prekey
    FROM public.device_signed_prekeys
    WHERE device_id = p_recipient_device_id AND is_active = true
    ORDER BY created_at DESC
    LIMIT 1;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Target device has no active signed prekey';
    END IF;

    -- Atomically claim 1 unconsumed One-Time PreKey using FOR UPDATE SKIP LOCKED
    SELECT id, key_id, public_key
    INTO v_opk
    FROM public.device_one_time_prekeys
    WHERE device_id = p_recipient_device_id AND is_consumed = false
    ORDER BY key_id ASC
    FOR UPDATE SKIP LOCKED
    LIMIT 1;

    IF FOUND THEN
        UPDATE public.device_one_time_prekeys
        SET is_consumed = true, consumed_at = now()
        WHERE id = v_opk.id;
    END IF;

    -- Build and return prekey bundle JSON
    v_result := jsonb_build_object(
        'device_id', p_recipient_device_id,
        'user_id', v_device.user_id,
        'registration_id', COALESCE(v_device.registration_id, 0),
        'identity_key', v_identity.identity_public_key,
        'signed_prekey_id', v_signed_prekey.key_id,
        'signed_prekey', v_signed_prekey.public_key,
        'signed_prekey_signature', v_signed_prekey.signature,
        'one_time_prekey_id', CASE WHEN v_opk.id IS NOT NULL THEN v_opk.key_id ELSE NULL END,
        'one_time_prekey', CASE WHEN v_opk.id IS NOT NULL THEN v_opk.public_key ELSE NULL END
    );

    RETURN v_result;
END;
$$;

--------------------------------------------------------------------------------
-- 8. HARDENED UNCONSUMED OPK COUNT RPC FUNCTION (OWNER ONLY)
--------------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.get_unconsumed_one_time_prekey_count(p_device_id UUID)
RETURNS INTEGER
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    v_device_owner UUID;
    v_count INTEGER;
BEGIN
    -- Security Check: Caller must be authenticated
    IF auth.role() IS NULL OR auth.role() != 'authenticated' THEN
        RAISE EXCEPTION 'Authentication required';
    END IF;

    -- Fetch device owner
    SELECT user_id INTO v_device_owner
    FROM public.devices
    WHERE id = p_device_id AND status = 'ACTIVE';

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Active device not found';
    END IF;

    -- Security Check: Only device owner can query OPK count
    IF v_device_owner != auth.uid() THEN
        RAISE EXCEPTION 'Access denied: Only device owner can query prekey count';
    END IF;

    SELECT COUNT(*)::INTEGER
    INTO v_count
    FROM public.device_one_time_prekeys
    WHERE device_id = p_device_id AND is_consumed = false;
    
    RETURN v_count;
END;
$$;

--------------------------------------------------------------------------------
-- 9. PRIVILEGE MANAGEMENT (REVOKE PUBLIC, GRANT AUTHENTICATED)
--------------------------------------------------------------------------------
REVOKE EXECUTE ON FUNCTION public.claim_prekey_bundle(UUID) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION public.get_unconsumed_one_time_prekey_count(UUID) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION public.claim_prekey_bundle(UUID) TO authenticated;
GRANT EXECUTE ON FUNCTION public.get_unconsumed_one_time_prekey_count(UUID) TO authenticated;
