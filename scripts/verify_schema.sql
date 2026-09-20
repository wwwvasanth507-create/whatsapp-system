-- CampusChat Verification SQL Script
-- Run this script against your Supabase PostgreSQL database to verify table structures, indexes, RLS policies, and storage setup.

DO $$
DECLARE
    missing_tables TEXT[] := '{}';
    tbl TEXT;
    expected_tables TEXT[] := ARRAY[
        'profiles',
        'devices',
        'conversations',
        'conversation_members',
        'messages',
        'message_deliveries',
        'encrypted_files',
        'push_tokens',
        'encryption_keys_metadata'
    ];
    rls_disabled_tables TEXT[] := '{}';
    bucket_count INT;
    bucket_is_public BOOLEAN;
BEGIN
    RAISE NOTICE '==================================================';
    RAISE NOTICE 'CampusChat Backend Schema Verification';
    RAISE NOTICE '==================================================';

    -- 1. Verify Table Existence
    FOREACH tbl IN ARRAY expected_tables LOOP
        IF NOT EXISTS (
            SELECT 1 
            FROM information_schema.tables 
            WHERE table_schema = 'public' 
              AND table_name = tbl
        ) THEN
            missing_tables := array_append(missing_tables, tbl);
        END IF;
    END LOOP;

    IF array_length(missing_tables, 1) IS NOT NULL THEN
        RAISE EXCEPTION 'VERIFICATION FAILED: Missing tables: %', missing_tables;
    ELSE
        RAISE NOTICE '[OK] All 9 required tables exist in public schema.';
    END IF;

    -- 2. Verify RLS is Enabled
    FOREACH tbl IN ARRAY expected_tables LOOP
        IF NOT EXISTS (
            SELECT 1 
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'public'
              AND c.relname = tbl
              AND c.relrowsecurity = true
        ) THEN
            rls_disabled_tables := array_append(rls_disabled_tables, tbl);
        END IF;
    END LOOP;

    IF array_length(rls_disabled_tables, 1) IS NOT NULL THEN
        RAISE EXCEPTION 'VERIFICATION FAILED: Tables with RLS disabled: %', rls_disabled_tables;
    ELSE
        RAISE NOTICE '[OK] Row Level Security (RLS) is enabled on all 9 tables.';
    END IF;

    -- 3. Verify Storage Bucket Configuration
    SELECT count(*), COALESCE(bool_or(public), false)
    INTO bucket_count, bucket_is_public
    FROM storage.buckets
    WHERE id = 'encrypted_temp_files';

    IF bucket_count = 0 THEN
        RAISE EXCEPTION 'VERIFICATION FAILED: Storage bucket "encrypted_temp_files" does not exist.';
    ELSIF bucket_is_public THEN
        RAISE EXCEPTION 'VERIFICATION FAILED: Storage bucket "encrypted_temp_files" is PUBLIC! It must be PRIVATE.';
    ELSE
        RAISE NOTICE '[OK] Private storage bucket "encrypted_temp_files" exists and is strictly PRIVATE.';
    END IF;

    -- 4. Verify Critical Indexes
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_messages_conversation_id') THEN
        RAISE WARNING 'Index idx_messages_conversation_id missing.';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_messages_sender_id') THEN
        RAISE WARNING 'Index idx_messages_sender_id missing.';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_messages_expires_at') THEN
        RAISE WARNING 'Index idx_messages_expires_at missing.';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_message_deliveries_message_id') THEN
        RAISE WARNING 'Index idx_message_deliveries_message_id missing.';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_message_deliveries_recipient_id') THEN
        RAISE WARNING 'Index idx_message_deliveries_recipient_id missing.';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_message_deliveries_status') THEN
        RAISE WARNING 'Index idx_message_deliveries_status missing.';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_devices_user_id') THEN
        RAISE WARNING 'Index idx_devices_user_id missing.';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_push_tokens_user_id') THEN
        RAISE WARNING 'Index idx_push_tokens_user_id missing.';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_encrypted_files_message_id') THEN
        RAISE WARNING 'Index idx_encrypted_files_message_id missing.';
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE indexname = 'idx_encrypted_files_expires_at') THEN
        RAISE WARNING 'Index idx_encrypted_files_expires_at missing.';
    END IF;

    RAISE NOTICE '[OK] Key indexes check completed successfully.';
    RAISE NOTICE '==================================================';
    RAISE NOTICE 'ALL VERIFICATION CHECKS PASSED SUCCESSFULLY!';
    RAISE NOTICE '==================================================';
END $$;
