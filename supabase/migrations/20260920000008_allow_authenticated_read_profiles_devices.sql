-- CampusChat Supabase Migration: 08 - Allow Authenticated Read Profiles and Devices
-- Purpose: Grants authenticated users SELECT access to profiles and device metadata for campus user discovery and Signal recipient device resolution.

--------------------------------------------------------------------------------
-- 1. PROFILES TABLE RLS
--------------------------------------------------------------------------------
DROP POLICY IF EXISTS "users_read_own_profile" ON public.profiles;
DROP POLICY IF EXISTS "authenticated_read_all_profiles" ON public.profiles;

CREATE POLICY "authenticated_read_all_profiles"
    ON public.profiles
    FOR SELECT
    TO authenticated
    USING (auth.role() = 'authenticated');

--------------------------------------------------------------------------------
-- 2. DEVICES TABLE RLS
--------------------------------------------------------------------------------
DROP POLICY IF EXISTS "users_select_own_devices" ON public.devices;
DROP POLICY IF EXISTS "authenticated_read_all_devices" ON public.devices;

CREATE POLICY "authenticated_read_all_devices"
    ON public.devices
    FOR SELECT
    TO authenticated
    USING (auth.role() = 'authenticated');
