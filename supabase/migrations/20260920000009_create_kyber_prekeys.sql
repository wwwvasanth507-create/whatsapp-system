-- CampusChat Supabase Migration: 09 - Signal Protocol Post-Quantum Kyber PreKeys
-- Purpose: Server-side Kyber (ML-KEM) public key directory for Signal PQX3DH session establishment.
-- Security Rule: Strictly stores PUBLIC cryptographic material and signatures only. NO PRIVATE KEYS EVER STORED.

--------------------------------------------------------------------------------
-- 1. DEVICE KYBER PREKEYS TABLE
--------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS public.device_kyber_prekeys (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    device_id UUID NOT NULL,
    user_id UUID NOT NULL,
    key_id INTEGER NOT NULL,
    public_key TEXT NOT NULL,
    signature TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_device_kyber_prekey_user FOREIGN KEY (device_id, user_id) 
        REFERENCES public.devices(id, user_id) ON DELETE CASCADE,
    CONSTRAINT unique_device_kyber_prekey UNIQUE (device_id, key_id)
);

COMMENT ON TABLE public.device_kyber_prekeys IS 'Signal Kyber (ML-KEM) Post-Quantum PreKeys with Ed25519 signatures per device.';

--------------------------------------------------------------------------------
-- 2. INDEXES
--------------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_device_kyber_prekeys_lookup 
    ON public.device_kyber_prekeys(device_id, is_active) WHERE is_active = true;

--------------------------------------------------------------------------------
-- 3. HARDENED ROW LEVEL SECURITY (RLS)
--------------------------------------------------------------------------------
ALTER TABLE public.device_kyber_prekeys ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "authenticated_select_active_device_kyber_prekeys" ON public.device_kyber_prekeys;
CREATE POLICY "authenticated_select_active_device_kyber_prekeys"
    ON public.device_kyber_prekeys FOR SELECT TO authenticated
    USING (
        EXISTS (
            SELECT 1 FROM public.devices d
            WHERE d.id = device_kyber_prekeys.device_id AND d.status = 'ACTIVE'
        )
    );

DROP POLICY IF EXISTS "owner_manage_device_kyber_prekeys" ON public.device_kyber_prekeys;
CREATE POLICY "owner_manage_device_kyber_prekeys"
    ON public.device_kyber_prekeys FOR ALL TO authenticated
    USING (
        user_id = auth.uid()
        AND EXISTS (
            SELECT 1 FROM public.devices d
            WHERE d.id = device_kyber_prekeys.device_id AND d.user_id = auth.uid() AND d.status = 'ACTIVE'
        )
    )
    WITH CHECK (
        user_id = auth.uid()
        AND EXISTS (
            SELECT 1 FROM public.devices d
            WHERE d.id = device_kyber_prekeys.device_id AND d.user_id = auth.uid() AND d.status = 'ACTIVE'
        )
    );

--------------------------------------------------------------------------------
-- 4. UPDATE ATOMIC PREKEY CLAIM RPC FUNCTION (INCLUDE KYBER PREKEY)
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
    v_kyber_prekey RECORD;
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

    -- Fetch Active Kyber Post-Quantum PreKey
    SELECT key_id, public_key, signature
    INTO v_kyber_prekey
    FROM public.device_kyber_prekeys
    WHERE device_id = p_recipient_device_id AND is_active = true
    ORDER BY created_at DESC
    LIMIT 1;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'Target device has no active Kyber prekey';
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
        'kyber_prekey_id', v_kyber_prekey.key_id,
        'kyber_prekey', v_kyber_prekey.public_key,
        'kyber_prekey_signature', v_kyber_prekey.signature,
        'one_time_prekey_id', CASE WHEN v_opk.id IS NOT NULL THEN v_opk.key_id ELSE NULL END,
        'one_time_prekey', CASE WHEN v_opk.id IS NOT NULL THEN v_opk.public_key ELSE NULL END
    );

    RETURN v_result;
END;
$$;

GRANT EXECUTE ON FUNCTION public.claim_prekey_bundle(UUID) TO authenticated;
