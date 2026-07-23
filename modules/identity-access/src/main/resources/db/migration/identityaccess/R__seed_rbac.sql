-- identity-access — the RBAC catalog. REPEATABLE migration: Flyway re-runs it whenever this file's
-- checksum changes, so adding a permission or recomposing a role is an edit here rather than a new
-- versioned migration each time.
--
-- IT MUST THEREFORE BE IDEMPOTENT. Every statement below either inserts or updates; none assumes an empty
-- table. Running it twice must leave the database exactly as running it once did.
--
-- THE CODES HERE MUST MATCH tz.co.otapp.buscore.identityaccess.Permissions EXACTLY, in both directions.
-- A code named by a route but missing here refuses everyone forever, silently, and no integration test
-- catches it — a test runs as an administrator granted every SEEDED code, or as ROOT which bypasses the
-- check. PermissionCatalogTest is what holds the two lists together.

-- ───────────────────────────────── permissions ─────────────────────────────────
--
-- gen_random_uuid() rather than uuidv7(): these are seed rows, few and written once, so the index
-- locality that motivates v7 for application inserts buys nothing here. The application still mints v7
-- in Java for every row it creates.

INSERT INTO permissions (uid, code, description, created_at, updated_at)
VALUES
    (gen_random_uuid(), 'ROLE.READ',       'See which roles exist and what each one confers.',      now(), now()),
    (gen_random_uuid(), 'ROLE.GRANT',      'Give a staff member a role.',                            now(), now()),
    (gen_random_uuid(), 'ROLE.REVOKE',     'Take a role away from a staff member.',                  now(), now()),
    (gen_random_uuid(), 'PERMISSION.READ', 'See the catalog of permissions, to compose a role.',     now(), now()),
    (gen_random_uuid(), 'STAFF.READ',      'See staff accounts other than one''s own.',              now(), now()),
    (gen_random_uuid(), 'STAFF.CREATE',    'Create a staff account.',                                now(), now()),
    (gen_random_uuid(), 'STAFF.SUSPEND',   'Withdraw a staff member''s access, for now or for good.', now(), now()),
    (gen_random_uuid(), 'STAFF.RESTORE',   'Return a withdrawn staff account to use.',               now(), now()),
    (gen_random_uuid(), 'STAFF.OPERATOR_LINK',
     'Attach an operator to a staff member, widening whose rows they reach.',                        now(), now()),
    (gen_random_uuid(), 'STAFF.OPERATOR_UNLINK',
     'Detach an operator from a staff member, narrowing whose rows they reach.',                     now(), now())
ON CONFLICT (code) DO UPDATE
    SET description = EXCLUDED.description,
        updated_at  = now();

-- ─────────────────────────────────── roles ───────────────────────────────────
--
-- ROOT is absent, and its absence is the design. It holds no role and appears nowhere in this file: its
-- authority is a single branch in PermissionGuard. Seeding it a role would make that branch look
-- redundant and invite its removal, at which point the break-glass identity is locked out of the system
-- it exists to rescue.

INSERT INTO roles (uid, code, name, description, holder_tenancy, created_at, updated_at)
VALUES
    (gen_random_uuid(), 'PLATFORM_ADMIN', 'Platform Administrator',
     'Full administrative access to the platform.', 'ADMIN', now(), now()),
    (gen_random_uuid(), 'SUPPORT', 'Support',
     'Read-only access, for answering questions without being able to change anything.', 'ADMIN', now(), now()),

    -- The first OPERATOR-held role, and the reason staff administration is bounded by company rather than
    -- reserved to the platform. Without it nobody but a platform administrator could add a colleague, so
    -- every routine staffing change at every operator would be a support ticket.
    --
    -- It carries the same staff-administration codes as PLATFORM_ADMIN and is not thereby equivalent to it:
    -- a permission says what a caller may do, and the service decides to whom. An operator administrator
    -- holding STAFF.CREATE can create only operator accounts, only in their own company.
    (gen_random_uuid(), 'OPERATOR_ADMIN', 'Operator Administrator',
     'Manage the staff accounts of one operating company.', 'OPERATOR', now(), now())
ON CONFLICT (code) DO UPDATE
    SET name        = EXCLUDED.name,
        description = EXCLUDED.description,
        updated_at  = now();

-- ───────────────────────────── role → permission ─────────────────────────────
--
-- EXPLICIT MEMBERSHIP, NEVER A PATTERN. Granting "everything matching %.READ" reads as convenient and
-- turns a rename into a privilege escalation: a permission renamed to end in .READ silently joins every
-- role defined that way. The cost of listing codes is that this block grows; the cost of not listing them
-- is a privilege change nobody made and nobody sees.
--
-- The DELETE makes the assignment authoritative rather than additive: a permission removed from a role
-- below is actually removed, instead of surviving because an earlier run had inserted it.

DELETE FROM role_permissions
WHERE role_id IN (SELECT id FROM roles WHERE code IN ('PLATFORM_ADMIN', 'SUPPORT', 'OPERATOR_ADMIN'));

INSERT INTO role_permissions (uid, role_id, permission_id, created_at, updated_at)
SELECT gen_random_uuid(), r.id, p.id, now(), now()
FROM roles r
         JOIN permissions p ON p.code IN ('ROLE.READ', 'ROLE.GRANT', 'ROLE.REVOKE', 'PERMISSION.READ',
                                          'STAFF.READ', 'STAFF.CREATE', 'STAFF.SUSPEND', 'STAFF.RESTORE',
                                          'STAFF.OPERATOR_LINK', 'STAFF.OPERATOR_UNLINK')
WHERE r.code = 'PLATFORM_ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO role_permissions (uid, role_id, permission_id, created_at, updated_at)
SELECT gen_random_uuid(), r.id, p.id, now(), now()
FROM roles r
         JOIN permissions p ON p.code IN ('ROLE.READ', 'PERMISSION.READ', 'STAFF.READ')
WHERE r.code = 'SUPPORT'
ON CONFLICT (role_id, permission_id) DO NOTHING;

-- No ROLE.GRANT or ROLE.REVOKE. An operator administrator manages who works there, not what any role
-- confers — and the roles they could grant are declared for ADMIN accounts anyway, so granting one would be
-- refused at the point of use. Leaving the permission out says so before the attempt rather than after.
INSERT INTO role_permissions (uid, role_id, permission_id, created_at, updated_at)
SELECT gen_random_uuid(), r.id, p.id, now(), now()
FROM roles r
         JOIN permissions p ON p.code IN ('ROLE.READ', 'STAFF.READ', 'STAFF.CREATE', 'STAFF.SUSPEND',
                                          'STAFF.RESTORE', 'STAFF.OPERATOR_LINK', 'STAFF.OPERATOR_UNLINK')
WHERE r.code = 'OPERATOR_ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;
