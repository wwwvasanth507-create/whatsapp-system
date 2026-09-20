-- CampusChat Supabase Migration: 02 - Row Level Security (RLS) Policies
-- Purpose: Enables Row Level Security on all tables and defines access control policies matching application security rules.

--------------------------------------------------------------------------------
-- 1. PROFILES TABLE RLS
--------------------------------------------------------------------------------
ALTER TABLE public.profiles ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "users_read_own_profile" ON public.profiles;
CREATE POLICY "users_read_own_profile"
    ON public.profiles
    FOR SELECT
    TO authenticated
    USING (auth.uid() = id);

DROP POLICY IF EXISTS "users_update_own_profile" ON public.profiles;
CREATE POLICY "users_update_own_profile"
    ON public.profiles
    FOR UPDATE
    TO authenticated
    USING (auth.uid() = id)
    WITH CHECK (auth.uid() = id);

DROP POLICY IF EXISTS "users_insert_own_profile" ON public.profiles;
CREATE POLICY "users_insert_own_profile"
    ON public.profiles
    FOR INSERT
    TO authenticated
    WITH CHECK (auth.uid() = id);

--------------------------------------------------------------------------------
-- 2. DEVICES TABLE RLS
--------------------------------------------------------------------------------
ALTER TABLE public.devices ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "users_select_own_devices" ON public.devices;
CREATE POLICY "users_select_own_devices"
    ON public.devices
    FOR SELECT
    TO authenticated
    USING (user_id = auth.uid());

DROP POLICY IF EXISTS "users_insert_own_devices" ON public.devices;
CREATE POLICY "users_insert_own_devices"
    ON public.devices
    FOR INSERT
    TO authenticated
    WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS "users_update_own_devices" ON public.devices;
CREATE POLICY "users_update_own_devices"
    ON public.devices
    FOR UPDATE
    TO authenticated
    USING (user_id = auth.uid())
    WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS "users_delete_own_devices" ON public.devices;
CREATE POLICY "users_delete_own_devices"
    ON public.devices
    FOR DELETE
    TO authenticated
    USING (user_id = auth.uid());

--------------------------------------------------------------------------------
-- 3. CONVERSATIONS TABLE RLS
--------------------------------------------------------------------------------
ALTER TABLE public.conversations ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "users_select_joined_conversations" ON public.conversations;
CREATE POLICY "users_select_joined_conversations"
    ON public.conversations
    FOR SELECT
    TO authenticated
    USING (
        EXISTS (
            SELECT 1 
            FROM public.conversation_members cm 
            WHERE cm.conversation_id = conversations.id 
              AND cm.user_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "authenticated_insert_conversations" ON public.conversations;
CREATE POLICY "authenticated_insert_conversations"
    ON public.conversations
    FOR INSERT
    TO authenticated
    WITH CHECK (auth.role() = 'authenticated');

--------------------------------------------------------------------------------
-- 4. CONVERSATION_MEMBERS TABLE RLS
--------------------------------------------------------------------------------
ALTER TABLE public.conversation_members ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "users_select_conversation_members" ON public.conversation_members;
CREATE POLICY "users_select_conversation_members"
    ON public.conversation_members
    FOR SELECT
    TO authenticated
    USING (
        user_id = auth.uid() 
        OR EXISTS (
            SELECT 1 
            FROM public.conversation_members cm 
            WHERE cm.conversation_id = conversation_members.conversation_id 
              AND cm.user_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "users_insert_conversation_members" ON public.conversation_members;
CREATE POLICY "users_insert_conversation_members"
    ON public.conversation_members
    FOR INSERT
    TO authenticated
    WITH CHECK (
        user_id = auth.uid() 
        OR EXISTS (
            SELECT 1 
            FROM public.conversation_members cm 
            WHERE cm.conversation_id = conversation_members.conversation_id 
              AND cm.user_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "users_delete_own_conversation_membership" ON public.conversation_members;
CREATE POLICY "users_delete_own_conversation_membership"
    ON public.conversation_members
    FOR DELETE
    TO authenticated
    USING (user_id = auth.uid());

--------------------------------------------------------------------------------
-- 5. MESSAGES TABLE RLS
--------------------------------------------------------------------------------
ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "users_select_conversation_messages" ON public.messages;
CREATE POLICY "users_select_conversation_messages"
    ON public.messages
    FOR SELECT
    TO authenticated
    USING (
        EXISTS (
            SELECT 1 
            FROM public.conversation_members cm 
            WHERE cm.conversation_id = messages.conversation_id 
              AND cm.user_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "users_insert_own_messages" ON public.messages;
CREATE POLICY "users_insert_own_messages"
    ON public.messages
    FOR INSERT
    TO authenticated
    WITH CHECK (
        sender_id = auth.uid() 
        AND EXISTS (
            SELECT 1 
            FROM public.conversation_members cm 
            WHERE cm.conversation_id = messages.conversation_id 
              AND cm.user_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "users_update_own_messages" ON public.messages;
CREATE POLICY "users_update_own_messages"
    ON public.messages
    FOR UPDATE
    TO authenticated
    USING (sender_id = auth.uid())
    WITH CHECK (sender_id = auth.uid());

DROP POLICY IF EXISTS "users_delete_own_messages" ON public.messages;
CREATE POLICY "users_delete_own_messages"
    ON public.messages
    FOR DELETE
    TO authenticated
    USING (sender_id = auth.uid());

--------------------------------------------------------------------------------
-- 6. MESSAGE_DELIVERIES TABLE RLS
--------------------------------------------------------------------------------
ALTER TABLE public.message_deliveries ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "users_select_message_deliveries" ON public.message_deliveries;
CREATE POLICY "users_select_message_deliveries"
    ON public.message_deliveries
    FOR SELECT
    TO authenticated
    USING (
        recipient_id = auth.uid()
        OR EXISTS (
            SELECT 1 
            FROM public.messages m 
            WHERE m.id = message_deliveries.message_id 
              AND m.sender_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "senders_insert_message_deliveries" ON public.message_deliveries;
CREATE POLICY "senders_insert_message_deliveries"
    ON public.message_deliveries
    FOR INSERT
    TO authenticated
    WITH CHECK (
        EXISTS (
            SELECT 1 
            FROM public.messages m 
            WHERE m.id = message_deliveries.message_id 
              AND m.sender_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "recipients_update_message_deliveries" ON public.message_deliveries;
CREATE POLICY "recipients_update_message_deliveries"
    ON public.message_deliveries
    FOR UPDATE
    TO authenticated
    USING (recipient_id = auth.uid())
    WITH CHECK (recipient_id = auth.uid());

--------------------------------------------------------------------------------
-- 7. ENCRYPTED_FILES TABLE RLS
--------------------------------------------------------------------------------
ALTER TABLE public.encrypted_files ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "users_select_encrypted_files" ON public.encrypted_files;
CREATE POLICY "users_select_encrypted_files"
    ON public.encrypted_files
    FOR SELECT
    TO authenticated
    USING (
        EXISTS (
            SELECT 1 
            FROM public.messages m 
            JOIN public.conversation_members cm ON m.conversation_id = cm.conversation_id 
            WHERE m.id = encrypted_files.message_id 
              AND cm.user_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "senders_insert_encrypted_files" ON public.encrypted_files;
CREATE POLICY "senders_insert_encrypted_files"
    ON public.encrypted_files
    FOR INSERT
    TO authenticated
    WITH CHECK (
        EXISTS (
            SELECT 1 
            FROM public.messages m 
            WHERE m.id = encrypted_files.message_id 
              AND m.sender_id = auth.uid()
        )
    );

DROP POLICY IF EXISTS "senders_delete_encrypted_files" ON public.encrypted_files;
CREATE POLICY "senders_delete_encrypted_files"
    ON public.encrypted_files
    FOR DELETE
    TO authenticated
    USING (
        EXISTS (
            SELECT 1 
            FROM public.messages m 
            WHERE m.id = encrypted_files.message_id 
              AND m.sender_id = auth.uid()
        )
    );

--------------------------------------------------------------------------------
-- 8. PUSH_TOKENS TABLE RLS
--------------------------------------------------------------------------------
ALTER TABLE public.push_tokens ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "users_select_own_push_tokens" ON public.push_tokens;
CREATE POLICY "users_select_own_push_tokens"
    ON public.push_tokens
    FOR SELECT
    TO authenticated
    USING (user_id = auth.uid());

DROP POLICY IF EXISTS "users_insert_own_push_tokens" ON public.push_tokens;
CREATE POLICY "users_insert_own_push_tokens"
    ON public.push_tokens
    FOR INSERT
    TO authenticated
    WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS "users_update_own_push_tokens" ON public.push_tokens;
CREATE POLICY "users_update_own_push_tokens"
    ON public.push_tokens
    FOR UPDATE
    TO authenticated
    USING (user_id = auth.uid())
    WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS "users_delete_own_push_tokens" ON public.push_tokens;
CREATE POLICY "users_delete_own_push_tokens"
    ON public.push_tokens
    FOR DELETE
    TO authenticated
    USING (user_id = auth.uid());

--------------------------------------------------------------------------------
-- 9. ENCRYPTION_KEYS_METADATA TABLE RLS
--------------------------------------------------------------------------------
ALTER TABLE public.encryption_keys_metadata ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "authenticated_select_public_keys" ON public.encryption_keys_metadata;
CREATE POLICY "authenticated_select_public_keys"
    ON public.encryption_keys_metadata
    FOR SELECT
    TO authenticated
    USING (auth.role() = 'authenticated');

DROP POLICY IF EXISTS "users_insert_own_public_keys" ON public.encryption_keys_metadata;
CREATE POLICY "users_insert_own_public_keys"
    ON public.encryption_keys_metadata
    FOR INSERT
    TO authenticated
    WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS "users_update_own_public_keys" ON public.encryption_keys_metadata;
CREATE POLICY "users_update_own_public_keys"
    ON public.encryption_keys_metadata
    FOR UPDATE
    TO authenticated
    USING (user_id = auth.uid())
    WITH CHECK (user_id = auth.uid());

DROP POLICY IF EXISTS "users_delete_own_public_keys" ON public.encryption_keys_metadata;
CREATE POLICY "users_delete_own_public_keys"
    ON public.encryption_keys_metadata
    FOR DELETE
    TO authenticated
    USING (user_id = auth.uid());
