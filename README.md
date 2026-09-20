# CampusChat - Supabase Backend Foundation (Step 1)

**CampusChat** is an Android-first private messaging application built for secure, end-to-end encrypted messaging with multi-device support, temporary encrypted media uploads, and push notifications.

This repository contains **STEP 1 ONLY: The Supabase Backend Foundation**.

---

## Architecture & Technology Stack

* **Database Engine**: PostgreSQL (via Supabase)
* **Authentication**: Supabase Auth (`auth.users`)
* **Storage**: Supabase Storage (Private Encrypted Buckets)
* **Security**: PostgreSQL Row Level Security (RLS) & Foreign Key Constraints

---

## 1. Tables Created

| Table Name | Description | Primary Key / References |
| :--- | :--- | :--- |
| [`profiles`](file:///c:/ll/whatsapp-system/supabase/migrations/20260920000000_create_campuschat_schema.sql#L9-L16) | User identity profile linked to Supabase Auth | `id UUID` REFERENCES `auth.users(id)` |
| [`devices`](file:///c:/ll/whatsapp-system/supabase/migrations/20260920000000_create_campuschat_schema.sql#L22-L31) | Tracks registered devices per user for multi-device support | `id UUID` (FK: `user_id` -> `profiles.id`) |
| [`conversations`](file:///c:/ll/whatsapp-system/supabase/migrations/20260920000000_create_campuschat_schema.sql#L36-L41) | Chat contexts (currently 1-to-1 direct messaging, designed for group expansion) | `id UUID` |
| [`conversation_members`](file:///c:/ll/whatsapp-system/supabase/migrations/20260920000000_create_campuschat_schema.sql#L46-L51) | Junction table establishing conversation membership | `(conversation_id, user_id)` Composite PK |
| [`messages`](file:///c:/ll/whatsapp-system/supabase/migrations/20260920000000_create_campuschat_schema.sql#L57-L67) | E2EE message payloads (ciphertext ONLY; NO plaintext columns) | `id UUID` (FK: `conversation_id`, `sender_id`) |
| [`message_deliveries`](file:///c:/ll/whatsapp-system/supabase/migrations/20260920000000_create_campuschat_schema.sql#L73-L84) | Delivery and read receipts tracked per recipient device | `id UUID` (FK: `message_id`, `recipient_id`, `recipient_device_id`) |
| [`encrypted_files`](file:///c:/ll/whatsapp-system/supabase/migrations/20260920000000_create_campuschat_schema.sql#L89-L98) | Metadata for temporary client-encrypted storage files | `id UUID` (FK: `message_id`) |
| [`push_tokens`](file:///c:/ll/whatsapp-system/supabase/migrations/20260920000000_create_campuschat_schema.sql#L103-L113) | FCM push tokens mapped to user devices | `id UUID` (FK: `user_id`, `device_id`) |
| [`encryption_keys_metadata`](file:///c:/ll/whatsapp-system/supabase/migrations/20260920000000_create_campuschat_schema.sql#L118-L128) | Public keys and key version metadata (NEVER stores private keys) | `id UUID` (FK: `user_id`, `device_id`) |

---

## 2. Table Descriptions & Purpose

1. **`profiles`**: Serves as the public-facing identity table for application users. Extends `auth.users` with `username`, `display_name`, and `avatar_url`. Automatically populated via PostgreSQL trigger on signup.
2. **`devices`**: Supports multi-device delivery. Each physical device registers a unique record to receive targeted E2EE key sessions and device-specific message delivery receipts.
3. **`conversations`**: Defines chat containers. Configured with a `type` constraint (`direct` vs `group`) to allow future group chat capability without database schema breaking changes.
4. **`conversation_members`**: Establishes user participation in conversations. Used as the core access control barrier for Row Level Security queries.
5. **`messages`**: Contains encrypted payload byte-strings/ciphertext, type metadata (`text`, `image`, `video`, `document`, `audio`, `signal`), encryption version, and TTL expiration timestamps (`expires_at`).
6. **`message_deliveries`**: Manages fan-out delivery status (`pending`, `delivered`, `read`, `failed`) per recipient device.
7. **`encrypted_files`**: Tracks temporary binary attachments stored in Supabase Storage. Stores chunk counts, encrypted byte size, storage path, and expiration timestamp (`expires_at`).
8. **`push_tokens`**: Maintains Firebase Cloud Messaging (FCM) tokens per device for push notification dispatch when devices are offline.
9. **`encryption_keys_metadata`**: Stores user and device public key bundles, identity keys, pre-keys, key versions, and cryptographic algorithm identifiers (e.g. `SignalProtocol-X25519`). **Private keys are strictly stored on client devices and are never transmitted or stored on Supabase.**

---

## 3. Row Level Security (RLS) Summary

All tables have Row Level Security (`ENABLE ROW LEVEL SECURITY`) explicitly turned on.

* **`profiles`**: Users can `SELECT`, `INSERT`, and `UPDATE` **only their own profile** (`auth.uid() = id`).
* **`devices`**: Users can `SELECT`, `INSERT`, `UPDATE`, and `DELETE` **only their own devices** (`user_id = auth.uid()`).
* **`push_tokens`**: Users can `SELECT`, `INSERT`, `UPDATE`, and `DELETE` **only their own push tokens** (`user_id = auth.uid()`).
* **`conversations`**: Users can `SELECT` **only conversations they belong to** (verified via `conversation_members`).
* **`conversation_members`**: Users can `SELECT` members of conversations they belong to and manage their own membership.
* **`messages`**:
  * Users can `SELECT` **only messages belonging to conversations they belong to**.
  * Users can `INSERT` messages **only if `sender_id = auth.uid()`** and they are a member of the target conversation.
  * Users **cannot modify or delete messages belonging to other users**.
* **`message_deliveries`**: Recipients can view and update (`delivered_at`, `read_at`) their own delivery records; senders can view delivery statuses of messages they created.
* **`encrypted_files`**: Users can `SELECT` file metadata only for messages belonging to their conversations; only the message sender can create or delete file metadata.
* **`encryption_keys_metadata`**: Authenticated users can `SELECT` public keys (to perform E2EE key agreement); users can `INSERT`, `UPDATE`, and `DELETE` **only their own public keys**.
* **Service-Role Operations**: Internal worker functions or edge triggers using `service_role` bypass RLS, while standard client SDK connections (anon/authenticated) are strictly bound by the RLS rules above.

---

## 4. Storage Bucket Configuration

* **Bucket Name**: `encrypted_temp_files`
* **Visibility**: **PRIVATE** (`public = false`)
* **Purpose**: Temporary storage for client-side encrypted attachment files (images, videos, documents, audio).
* **Storage RLS Policies**:
  * `authenticated_select_encrypted_storage`: Allows reading files only if the user is a member of the conversation associated with the storage path folder.
  * `authenticated_insert_encrypted_storage`: Allows uploading files into storage paths associated with conversations the user belongs to or their user ID folder.
  * `authenticated_delete_encrypted_storage`: Allows deletion of stored objects by the object owner.

---

## 5. Intentionally NOT Implemented Yet (Deferred to Future Steps)

The following capabilities are explicitly deferred from Step 1 per project requirements:
* Android UI & Chat Interface
* Client-side Signal Protocol / E2EE cryptographic key pair generation
* File upload UI / client attachment picker
* FCM push notification sender Edge Functions / relay dispatcher
* Relay node infrastructure
* Automated background TTL message & file cleanup cron workers (supported via `expires_at` column fields for future implementation)

---

## 6. How to Apply the Migration

### Option A: Using Supabase CLI (Recommended)

1. Ensure the Supabase CLI is installed and linked to your project:
   ```bash
   npx supabase link --project-ref <YOUR_PROJECT_REF>
   ```
2. Push all migration scripts:
   ```bash
   npx supabase db push
   ```

### Option B: Using Supabase Dashboard SQL Editor

1. Open your [Supabase Dashboard](https://supabase.com/dashboard).
2. Navigate to **SQL Editor**.
3. Open [`supabase/migrations/20260920000005_campuschat_all_in_one.sql`](file:///c:/ll/whatsapp-system/supabase/migrations/20260920000005_campuschat_all_in_one.sql).
4. Copy the entire file content, paste it into the SQL Editor, and click **Run**.

---

## 7. How to Verify the Database

Run the automated verification script located at [`scripts/verify_schema.sql`](file:///c:/ll/whatsapp-system/scripts/verify_schema.sql):

1. Open **Supabase Dashboard SQL Editor**.
2. Paste the contents of [`scripts/verify_schema.sql`](file:///c:/ll/whatsapp-system/scripts/verify_schema.sql).
3. Click **Run**.
4. The output notices will confirm:
   - All 9 required tables exist.
   - Row Level Security (RLS) is active on all 9 tables.
   - Storage bucket `encrypted_temp_files` exists and is strictly **PRIVATE**.
   - Indexes on `messages`, `message_deliveries`, `devices`, `push_tokens`, and `encrypted_files` exist.

---

## Critical Security Rule

> [!CAUTION]
> **NEVER include the Supabase `service_role` key inside the Android application.**
> The Android application must only use the public `anon` key along with authenticated user JWT tokens. All administrative or privileged tasks must be handled server-side via Supabase Edge Functions using RLS and service role key securely in environment variables.
