-- CampusChat Supabase Migration: 03 - Storage Bucket & Storage RLS
-- Purpose: Creates a PRIVATE storage bucket for temporary encrypted files and secures it using storage RLS policies.

--------------------------------------------------------------------------------
-- 1. CREATE PRIVATE STORAGE BUCKET
--------------------------------------------------------------------------------
INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
VALUES (
    'encrypted_temp_files',
    'encrypted_temp_files',
    false, -- STRICTLY PRIVATE (Not public)
    52428800, -- 50MB file size limit
    NULL -- Allows client-side encrypted blob/binary uploads
)
ON CONFLICT (id) DO UPDATE 
SET public = false,
    file_size_limit = EXCLUDED.file_size_limit;

--------------------------------------------------------------------------------
-- 2. STORAGE RLS POLICIES FOR 'encrypted_temp_files'
-- Note: RLS is enabled on storage.objects by default in Supabase.
--------------------------------------------------------------------------------

-- Policy 1: SELECT (Read/Download)
-- Authenticated users can read objects from 'encrypted_temp_files' only if the object path is structured as '{conversation_id}/{message_id}/{filename}'
-- and the user is a member of that conversation, OR if the file is in their user folder '{user_id}/...'.
DROP POLICY IF EXISTS "authenticated_select_encrypted_storage" ON storage.objects;
CREATE POLICY "authenticated_select_encrypted_storage"
    ON storage.objects
    FOR SELECT
    TO authenticated
    USING (
        bucket_id = 'encrypted_temp_files'
        AND (
            -- Case A: User path matches user_id
            (storage.foldername(name))[1] = auth.uid()::text
            OR
            -- Case B: Conversation path matches conversation membership
            EXISTS (
                SELECT 1 
                FROM public.conversation_members cm 
                WHERE cm.conversation_id::text = (storage.foldername(name))[1] 
                  AND cm.user_id = auth.uid()
            )
        )
    );

-- Policy 2: INSERT (Upload)
-- Users can upload files to 'encrypted_temp_files' into paths prefixed with their user_id or a conversation they belong to.
DROP POLICY IF EXISTS "authenticated_insert_encrypted_storage" ON storage.objects;
CREATE POLICY "authenticated_insert_encrypted_storage"
    ON storage.objects
    FOR INSERT
    TO authenticated
    WITH CHECK (
        bucket_id = 'encrypted_temp_files'
        AND (
            -- Case A: Uploading to user's folder
            (storage.foldername(name))[1] = auth.uid()::text
            OR
            -- Case B: Uploading to a conversation user belongs to
            EXISTS (
                SELECT 1 
                FROM public.conversation_members cm 
                WHERE cm.conversation_id::text = (storage.foldername(name))[1] 
                  AND cm.user_id = auth.uid()
            )
        )
    );

-- Policy 3: DELETE
-- Users can delete files in 'encrypted_temp_files' that they uploaded or own.
DROP POLICY IF EXISTS "authenticated_delete_encrypted_storage" ON storage.objects;
CREATE POLICY "authenticated_delete_encrypted_storage"
    ON storage.objects
    FOR DELETE
    TO authenticated
    USING (
        bucket_id = 'encrypted_temp_files'
        AND (
            owner = auth.uid()
            OR (storage.foldername(name))[1] = auth.uid()::text
        )
    );
