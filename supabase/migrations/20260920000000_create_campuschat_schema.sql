-- CampusChat Supabase Migration: 00 - Schema Creation
-- Purpose: Creates core domain tables, constraints, foreign keys, and defaults.

-- Enable UUID extension if not enabled
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 1. PROFILES TABLE
-- Stores user identity details referencing auth.users.
CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    username TEXT UNIQUE,
    display_name TEXT,
    avatar_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE public.profiles IS 'User profile identity information linked to Supabase Auth users.';
COMMENT ON COLUMN public.profiles.id IS 'References auth.users.id.';

-- 2. DEVICES TABLE
-- Tracks multiple devices registered per user.
CREATE TABLE IF NOT EXISTS public.devices (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    device_name TEXT NOT NULL,
    platform TEXT NOT NULL,
    fcm_token TEXT,
    last_seen_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE public.devices IS 'Registered devices per user for multi-device messaging and notification dispatch.';

-- 3. CONVERSATIONS TABLE
-- Chat contexts supporting 1-to-1 and future group chats.
CREATE TABLE IF NOT EXISTS public.conversations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type TEXT NOT NULL DEFAULT 'direct' CHECK (type IN ('direct', 'group')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE public.conversations IS 'Conversations container (direct or group).';

-- 4. CONVERSATION_MEMBERS TABLE
-- Maps users to conversations.
CREATE TABLE IF NOT EXISTS public.conversation_members (
    conversation_id UUID NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (conversation_id, user_id)
);

COMMENT ON TABLE public.conversation_members IS 'Junction table tracking membership in conversations.';

-- 5. MESSAGES TABLE
-- Encrypted message payloads. ONLY ciphertext is stored; NO plaintext columns exist.
CREATE TABLE IF NOT EXISTS public.messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    sender_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    message_type TEXT NOT NULL DEFAULT 'text' CHECK (message_type IN ('text', 'image', 'video', 'document', 'audio', 'signal')),
    encrypted_payload TEXT NOT NULL, -- Ciphertext only. NEVER store plaintext!
    encryption_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ,
    status TEXT NOT NULL DEFAULT 'sent' CHECK (status IN ('sent', 'delivered', 'read', 'expired', 'deleted'))
);

COMMENT ON TABLE public.messages IS 'Stores end-to-end encrypted message payloads (ciphertext only).';
COMMENT ON COLUMN public.messages.encrypted_payload IS 'Strictly contains encrypted ciphertext. No plaintext columns exist.';

-- 6. MESSAGE_DELIVERIES TABLE
-- Per-recipient device message delivery status tracking.
CREATE TABLE IF NOT EXISTS public.message_deliveries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id UUID NOT NULL REFERENCES public.messages(id) ON DELETE CASCADE,
    recipient_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    recipient_device_id UUID REFERENCES public.devices(id) ON DELETE SET NULL,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'delivered', 'read', 'failed')),
    delivered_at TIMESTAMPTZ,
    read_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE public.message_deliveries IS 'Delivery status tracking per recipient device.';

-- 7. ENCRYPTED_FILES TABLE
-- Metadata for temporary encrypted attachment files.
CREATE TABLE IF NOT EXISTS public.encrypted_files (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id UUID NOT NULL REFERENCES public.messages(id) ON DELETE CASCADE,
    storage_path TEXT NOT NULL UNIQUE,
    encrypted_size BIGINT NOT NULL,
    chunk_count INTEGER NOT NULL DEFAULT 1,
    encryption_version INTEGER NOT NULL DEFAULT 1,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE public.encrypted_files IS 'Metadata for temporary encrypted media/file objects.';

-- 8. PUSH_TOKENS TABLE
-- Firebase Cloud Messaging (FCM) tokens mapped to user devices.
CREATE TABLE IF NOT EXISTS public.push_tokens (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    device_id UUID NOT NULL REFERENCES public.devices(id) ON DELETE CASCADE,
    token TEXT NOT NULL,
    platform TEXT NOT NULL DEFAULT 'android',
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON TABLE public.push_tokens IS 'FCM and APNs push notification registration tokens.';

-- 9. ENCRYPTION_KEYS_METADATA TABLE
-- Public key metadata for E2EE key agreement. Private keys MUST NEVER be stored.
CREATE TABLE IF NOT EXISTS public.encryption_keys_metadata (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    device_id UUID REFERENCES public.devices(id) ON DELETE CASCADE,
    key_version INTEGER NOT NULL DEFAULT 1,
    public_key TEXT NOT NULL, -- Public key ONLY! NEVER store private keys!
    algorithm TEXT NOT NULL DEFAULT 'SignalProtocol-X25519',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT unique_user_device_key_version UNIQUE (user_id, device_id, key_version)
);

COMMENT ON TABLE public.encryption_keys_metadata IS 'Metadata for public encryption keys and versioning. Private keys are never stored.';
