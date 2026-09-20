-- =============================================================================
-- CampusChat Supabase Migration: ALL-IN-ONE CONSOLIDATED SCRIPT
-- Application: CampusChat (Android-first Private Messaging Backend)
-- Description: Complete backend database schema, indexes, RLS policies,
--              private storage configuration, functions, and triggers.
-- =============================================================================

BEGIN;

-- -----------------------------------------------------------------------------
-- SECTION 1: EXTENSIONS & SCHEMAS
-- -----------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- -----------------------------------------------------------------------------
-- SECTION 2: TABLE CREATION
-- -----------------------------------------------------------------------------

-- 1. PROFILES
CREATE TABLE IF NOT EXISTS public.profiles (
    id UUID PRIMARY KEY REFERENCES auth.users(id) ON DELETE CASCADE,
    username TEXT UNIQUE,
    display_name TEXT,
    avatar_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 2. DEVICES
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

-- 3. CONVERSATIONS
CREATE TABLE IF NOT EXISTS public.conversations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type TEXT NOT NULL DEFAULT 'direct' CHECK (type IN ('direct', 'group')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 4. CONVERSATION_MEMBERS
CREATE TABLE IF NOT EXISTS public.conversation_members (
    conversation_id UUID NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (conversation_id, user_id)
);

-- 5. MESSAGES
CREATE TABLE IF NOT EXISTS public.messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id UUID NOT NULL REFERENCES public.conversations(id) ON DELETE CASCADE,
    sender_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    message_type TEXT NOT NULL DEFAULT 'text' CHECK (message_type IN ('text', 'image', 'video', 'document', 'audio', 'signal')),
    encrypted_payload TEXT NOT NULL, -- CIPHERTEXT ONLY. NO PLAINTEXT COLUMNS!
    encryption_version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ,
    status TEXT NOT NULL DEFAULT 'sent' CHECK (status IN ('sent', 'delivered', 'read', 'expired', 'deleted'))
);

-- 6. MESSAGE_DELIVERIES
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

-- 7. ENCRYPTED_FILES
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

-- 8. PUSH_TOKENS
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

-- 9. ENCRYPTION_KEYS_METADATA
CREATE TABLE IF NOT EXISTS public.encryption_keys_metadata (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    device_id UUID REFERENCES public.devices(id) ON DELETE CASCADE,
    key_version INTEGER NOT NULL DEFAULT 1,
    public_key TEXT NOT NULL, -- PUBLIC KEYS ONLY. NEVER STORE PRIVATE KEYS!
    algorithm TEXT NOT NULL DEFAULT 'SignalProtocol-X25519',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT unique_user_device_key_version UNIQUE (user_id, device_id, key_version)
);

-- -----------------------------------------------------------------------------
-- SECTION 3: INDEXES
-- -----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_messages_conversation_id ON public.messages (conversation_id);
CREATE INDEX IF NOT EXISTS idx_messages_sender_id ON public.messages (sender_id);
CREATE INDEX IF NOT EXISTS idx_messages_expires_at ON public.messages (expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_message_deliveries_message_id ON public.message_deliveries (message_id);
CREATE INDEX IF NOT EXISTS idx_message_deliveries_recipient_id ON public.message_deliveries (recipient_id);
CREATE INDEX IF NOT EXISTS idx_message_deliveries_status ON public.message_deliveries (status);
CREATE INDEX IF NOT EXISTS idx_devices_user_id ON public.devices (user_id);
CREATE INDEX IF NOT EXISTS idx_push_tokens_user_id ON public.push_tokens (user_id);
CREATE INDEX IF NOT EXISTS idx_push_tokens_device_id ON public.push_tokens (device_id);
CREATE INDEX IF NOT EXISTS idx_encrypted_files_message_id ON public.encrypted_files (message_id);
CREATE INDEX IF NOT EXISTS idx_encrypted_files_expires_at ON public.encrypted_files (expires_at) WHERE expires_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_conversation_members_user_id ON public.conversation_members (user_id);
CREATE INDEX IF NOT EXISTS idx_encryption_keys_metadata_user_id ON public.encryption_keys_metadata (user_id);

-- -----------------------------------------------------------------------------
-- SECTION 4: ROW LEVEL SECURITY (RLS) POLICIES
-- -----------------------------------------------------------------------------

-- PROFILES
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "users_read_own_profile" ON public.profiles;
CREATE POLICY "users_read_own_profile" ON public.profiles FOR SELECT TO authenticated USING (auth.uid() = id);

DROP POLICY IF EXISTS "users_update_own_profile" ON public.profiles;
CREATE POLICY "users_update_own_profile" ON public.profiles FOR UPDATE TO authenticated USING (auth.uid() = id) WITH CHECK (auth.uid() = id);

DROP POLICY IF EXISTS "users_insert_own_profile" ON public.profiles;
CREATE POLICY "users_insert_own_profile" ON public.profiles FOR INSERT TO authenticated WITH CHECK (auth.uid() = id);

-- DEVICES
ALTER TABLE public.devices ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "users_select_own_devices" ON public.devices;
CREATE POLICY "users_select_own_devices" ON public.devices FOR SELECT TO authenticated USING (user_id = auth.uid());

DROP POLICY IF EXISTS "users_insert_own_devices" ON public.devices;
CREATE POLICY "users_insert_own_devices" ON public.devices FOR INSERT TO authenticated WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS "users_update_own_devices" ON public.devices;
CREATE POLICY "users_update_own_devices" ON public.devices FOR UPDATE TO authenticated USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS "users_delete_own_devices" ON public.devices;
CREATE POLICY "users_delete_own_devices" ON public.devices FOR DELETE TO authenticated USING (user_id = auth.uid());

-- CONVERSATIONS
ALTER TABLE public.conversations ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "users_select_joined_conversations" ON public.conversations;
CREATE POLICY "users_select_joined_conversations" ON public.conversations FOR SELECT TO authenticated
    USING (EXISTS (SELECT 1 FROM public.conversation_members cm WHERE cm.conversation_id = conversations.id AND cm.user_id = auth.uid()));

DROP POLICY IF EXISTS "authenticated_insert_conversations" ON public.conversations;
CREATE POLICY "authenticated_insert_conversations" ON public.conversations FOR INSERT TO authenticated WITH CHECK (auth.role() = 'authenticated');

-- CONVERSATION_MEMBERS
ALTER TABLE public.conversation_members ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "users_select_conversation_members" ON public.conversation_members;
CREATE POLICY "users_select_conversation_members" ON public.conversation_members FOR SELECT TO authenticated
    USING (user_id = auth.uid() OR EXISTS (SELECT 1 FROM public.conversation_members cm WHERE cm.conversation_id = conversation_members.conversation_id AND cm.user_id = auth.uid()));

DROP POLICY IF EXISTS "users_insert_conversation_members" ON public.conversation_members;
CREATE POLICY "users_insert_conversation_members" ON public.conversation_members FOR INSERT TO authenticated
    WITH CHECK (user_id = auth.uid() OR EXISTS (SELECT 1 FROM public.conversation_members cm WHERE cm.conversation_id = conversation_members.conversation_id AND cm.user_id = auth.uid()));

DROP POLICY IF EXISTS "users_delete_own_conversation_membership" ON public.conversation_members;
CREATE POLICY "users_delete_own_conversation_membership" ON public.conversation_members FOR DELETE TO authenticated USING (user_id = auth.uid());

-- MESSAGES
ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "users_select_conversation_messages" ON public.messages;
CREATE POLICY "users_select_conversation_messages" ON public.messages FOR SELECT TO authenticated
    USING (EXISTS (SELECT 1 FROM public.conversation_members cm WHERE cm.conversation_id = messages.conversation_id AND cm.user_id = auth.uid()));

DROP POLICY IF EXISTS "users_insert_own_messages" ON public.messages;
CREATE POLICY "users_insert_own_messages" ON public.messages FOR INSERT TO authenticated
    WITH CHECK (sender_id = auth.uid() AND EXISTS (SELECT 1 FROM public.conversation_members cm WHERE cm.conversation_id = messages.conversation_id AND cm.user_id = auth.uid()));

DROP POLICY IF EXISTS "users_update_own_messages" ON public.messages;
CREATE POLICY "users_update_own_messages" ON public.messages FOR UPDATE TO authenticated USING (sender_id = auth.uid()) WITH CHECK (sender_id = auth.uid());

DROP POLICY IF EXISTS "users_delete_own_messages" ON public.messages;
CREATE POLICY "users_delete_own_messages" ON public.messages FOR DELETE TO authenticated USING (sender_id = auth.uid());

-- MESSAGE_DELIVERIES
ALTER TABLE public.message_deliveries ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "users_select_message_deliveries" ON public.message_deliveries;
CREATE POLICY "users_select_message_deliveries" ON public.message_deliveries FOR SELECT TO authenticated
    USING (recipient_id = auth.uid() OR EXISTS (SELECT 1 FROM public.messages m WHERE m.id = message_deliveries.message_id AND m.sender_id = auth.uid()));

DROP POLICY IF EXISTS "senders_insert_message_deliveries" ON public.message_deliveries;
CREATE POLICY "senders_insert_message_deliveries" ON public.message_deliveries FOR INSERT TO authenticated
    WITH CHECK (EXISTS (SELECT 1 FROM public.messages m WHERE m.id = message_deliveries.message_id AND m.sender_id = auth.uid()));

DROP POLICY IF EXISTS "recipients_update_message_deliveries" ON public.message_deliveries;
CREATE POLICY "recipients_update_message_deliveries" ON public.message_deliveries FOR UPDATE TO authenticated USING (recipient_id = auth.uid()) WITH CHECK (recipient_id = auth.uid());

-- ENCRYPTED_FILES
ALTER TABLE public.encrypted_files ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "users_select_encrypted_files" ON public.encrypted_files;
CREATE POLICY "users_select_encrypted_files" ON public.encrypted_files FOR SELECT TO authenticated
    USING (EXISTS (SELECT 1 FROM public.messages m JOIN public.conversation_members cm ON m.conversation_id = cm.conversation_id WHERE m.id = encrypted_files.message_id AND cm.user_id = auth.uid()));

DROP POLICY IF EXISTS "senders_insert_encrypted_files" ON public.encrypted_files;
CREATE POLICY "senders_insert_encrypted_files" ON public.encrypted_files FOR INSERT TO authenticated
    WITH CHECK (EXISTS (SELECT 1 FROM public.messages m WHERE m.id = encrypted_files.message_id AND m.sender_id = auth.uid()));

DROP POLICY IF EXISTS "senders_delete_encrypted_files" ON public.encrypted_files;
CREATE POLICY "senders_delete_encrypted_files" ON public.encrypted_files FOR DELETE TO authenticated
    USING (EXISTS (SELECT 1 FROM public.messages m WHERE m.id = encrypted_files.message_id AND m.sender_id = auth.uid()));

-- PUSH_TOKENS
ALTER TABLE public.push_tokens ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "users_select_own_push_tokens" ON public.push_tokens;
CREATE POLICY "users_select_own_push_tokens" ON public.push_tokens FOR SELECT TO authenticated USING (user_id = auth.uid());

DROP POLICY IF EXISTS "users_insert_own_push_tokens" ON public.push_tokens;
CREATE POLICY "users_insert_own_push_tokens" ON public.push_tokens FOR INSERT TO authenticated WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS "users_update_own_push_tokens" ON public.push_tokens;
CREATE POLICY "users_update_own_push_tokens" ON public.push_tokens FOR UPDATE TO authenticated USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS "users_delete_own_push_tokens" ON public.push_tokens;
CREATE POLICY "users_delete_own_push_tokens" ON public.push_tokens FOR DELETE TO authenticated USING (user_id = auth.uid());

-- ENCRYPTION_KEYS_METADATA
ALTER TABLE public.encryption_keys_metadata ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "authenticated_select_public_keys" ON public.encryption_keys_metadata;
CREATE POLICY "authenticated_select_public_keys" ON public.encryption_keys_metadata FOR SELECT TO authenticated USING (auth.role() = 'authenticated');

DROP POLICY IF EXISTS "users_insert_own_public_keys" ON public.encryption_keys_metadata;
CREATE POLICY "users_insert_own_public_keys" ON public.encryption_keys_metadata FOR INSERT TO authenticated WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS "users_update_own_public_keys" ON public.encryption_keys_metadata;
CREATE POLICY "users_update_own_public_keys" ON public.encryption_keys_metadata FOR UPDATE TO authenticated USING (user_id = auth.uid()) WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS "users_delete_own_public_keys" ON public.encryption_keys_metadata;
CREATE POLICY "users_delete_own_public_keys" ON public.encryption_keys_metadata FOR DELETE TO authenticated USING (user_id = auth.uid());

-- -----------------------------------------------------------------------------
-- SECTION 5: STORAGE BUCKET & STORAGE RLS
-- Note: RLS is enabled on storage.objects by default in Supabase (owned by storage admin).
-- -----------------------------------------------------------------------------
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES ('encrypted_temp_files', 'encrypted_temp_files', false, 52428800, NULL)
ON CONFLICT (id) DO UPDATE SET public = false, file_size_limit = EXCLUDED.file_size_limit;

DROP POLICY IF EXISTS "authenticated_select_encrypted_storage" ON storage.objects;
CREATE POLICY "authenticated_select_encrypted_storage" ON storage.objects FOR SELECT TO authenticated
    USING (bucket_id = 'encrypted_temp_files' AND ((storage.foldername(name))[1] = auth.uid()::text OR EXISTS (SELECT 1 FROM public.conversation_members cm WHERE cm.conversation_id::text = (storage.foldername(name))[1] AND cm.user_id = auth.uid())));

DROP POLICY IF EXISTS "authenticated_insert_encrypted_storage" ON storage.objects;
CREATE POLICY "authenticated_insert_encrypted_storage" ON storage.objects FOR INSERT TO authenticated
    WITH CHECK (bucket_id = 'encrypted_temp_files' AND ((storage.foldername(name))[1] = auth.uid()::text OR EXISTS (SELECT 1 FROM public.conversation_members cm WHERE cm.conversation_id::text = (storage.foldername(name))[1] AND cm.user_id = auth.uid())));

DROP POLICY IF EXISTS "authenticated_delete_encrypted_storage" ON storage.objects;
CREATE POLICY "authenticated_delete_encrypted_storage" ON storage.objects FOR DELETE TO authenticated
    USING (bucket_id = 'encrypted_temp_files' AND (owner = auth.uid() OR (storage.foldername(name))[1] = auth.uid()::text));

-- -----------------------------------------------------------------------------
-- SECTION 6: FUNCTIONS & TRIGGERS
-- -----------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.handle_new_user()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER SET search_path = public
AS $$
BEGIN
    INSERT INTO public.profiles (id, username, display_name, avatar_url)
    VALUES (
        NEW.id,
        COALESCE(NEW.raw_user_meta_data->>'username', SPLIT_PART(NEW.email, '@', 1)),
        COALESCE(NEW.raw_user_meta_data->>'display_name', NEW.raw_user_meta_data->>'full_name', SPLIT_PART(NEW.email, '@', 1)),
        NEW.raw_user_meta_data->>'avatar_url'
    )
    ON CONFLICT (id) DO NOTHING;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS on_auth_user_created ON auth.users;
CREATE TRIGGER on_auth_user_created
    AFTER INSERT ON auth.users
    FOR EACH ROW EXECUTE FUNCTION public.handle_new_user();

CREATE OR REPLACE FUNCTION public.update_updated_at_column()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS set_profiles_updated_at ON public.profiles;
CREATE TRIGGER set_profiles_updated_at BEFORE UPDATE ON public.profiles FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

DROP TRIGGER IF EXISTS set_devices_updated_at ON public.devices;
CREATE TRIGGER set_devices_updated_at BEFORE UPDATE ON public.devices FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

DROP TRIGGER IF EXISTS set_conversations_updated_at ON public.conversations;
CREATE TRIGGER set_conversations_updated_at BEFORE UPDATE ON public.conversations FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

DROP TRIGGER IF EXISTS set_message_deliveries_updated_at ON public.message_deliveries;
CREATE TRIGGER set_message_deliveries_updated_at BEFORE UPDATE ON public.message_deliveries FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

DROP TRIGGER IF EXISTS set_push_tokens_updated_at ON public.push_tokens;
CREATE TRIGGER set_push_tokens_updated_at BEFORE UPDATE ON public.push_tokens FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

DROP TRIGGER IF EXISTS set_encryption_keys_metadata_updated_at ON public.encryption_keys_metadata;
CREATE TRIGGER set_encryption_keys_metadata_updated_at BEFORE UPDATE ON public.encryption_keys_metadata FOR EACH ROW EXECUTE FUNCTION public.update_updated_at_column();

COMMIT;
