-- CampusChat Supabase Migration: 01 - Performance Indexes
-- Purpose: Creates indexes required for efficient query execution, status filtering, TTL expiration checks, and relational lookups.

-- 1. MESSAGES INDEXES
CREATE INDEX IF NOT EXISTS idx_messages_conversation_id 
    ON public.messages (conversation_id);

CREATE INDEX IF NOT EXISTS idx_messages_sender_id 
    ON public.messages (sender_id);

CREATE INDEX IF NOT EXISTS idx_messages_expires_at 
    ON public.messages (expires_at) 
    WHERE expires_at IS NOT NULL;

-- 2. MESSAGE_DELIVERIES INDEXES
CREATE INDEX IF NOT EXISTS idx_message_deliveries_message_id 
    ON public.message_deliveries (message_id);

CREATE INDEX IF NOT EXISTS idx_message_deliveries_recipient_id 
    ON public.message_deliveries (recipient_id);

CREATE INDEX IF NOT EXISTS idx_message_deliveries_status 
    ON public.message_deliveries (status);

-- 3. DEVICES INDEXES
CREATE INDEX IF NOT EXISTS idx_devices_user_id 
    ON public.devices (user_id);

-- 4. PUSH_TOKENS INDEXES
CREATE INDEX IF NOT EXISTS idx_push_tokens_user_id 
    ON public.push_tokens (user_id);

CREATE INDEX IF NOT EXISTS idx_push_tokens_device_id 
    ON public.push_tokens (device_id);

-- 5. ENCRYPTED_FILES INDEXES
CREATE INDEX IF NOT EXISTS idx_encrypted_files_message_id 
    ON public.encrypted_files (message_id);

CREATE INDEX IF NOT EXISTS idx_encrypted_files_expires_at 
    ON public.encrypted_files (expires_at) 
    WHERE expires_at IS NOT NULL;

-- 6. CONVERSATION_MEMBERS INDEX
CREATE INDEX IF NOT EXISTS idx_conversation_members_user_id 
    ON public.conversation_members (user_id);

-- 7. ENCRYPTION_KEYS_METADATA INDEX
CREATE INDEX IF NOT EXISTS idx_encryption_keys_metadata_user_id 
    ON public.encryption_keys_metadata (user_id);
