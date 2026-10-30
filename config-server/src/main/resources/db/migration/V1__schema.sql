create table setting_definition (
    key           text primary key,
    type          text   not null,
    rules         jsonb  not null default '{}',
    default_value jsonb  not null,
    description   text   not null default '',
    version       bigint not null
);

create sequence config_version_seq start 1;

-- append-only source of truth; every accepted change set is one row
create table config_change (
    version         bigint primary key default nextval('config_version_seq'),
    tenant_id       text        not null,  -- '*' for DEFINITION changes
    author          text        not null,  -- JWT sub only
    reason          text        not null,
    kind            text        not null,  -- CHANGE | PROMOTE | ROLLBACK | DEFINITION
    operations      jsonb       not null,  -- [{op,level,scopeId,key,value}]; the definition JSON for DEFINITION
    idempotency_key text,
    request_hash    text,                  -- sha-256 of the canonical body, 409 when a key is reused with another body
    created_at      timestamptz not null default now()
);
create unique index config_change_idempotency on config_change (tenant_id, idempotency_key) where idempotency_key is not null;
create index config_change_tenant_version on config_change (tenant_id, version);

-- materialised current state, rebuildable from the log
create table config_override (
    tenant_id   text   not null,
    scope_level text   not null,
    scope_id    text   not null,
    key         text   not null,
    value       jsonb  not null,
    version     bigint not null,
    primary key (tenant_id, scope_level, scope_id, key)
);

create table app_user (
    username      text primary key,
    password_hash text not null,
    role          text not null,
    tenant_id     text
);
