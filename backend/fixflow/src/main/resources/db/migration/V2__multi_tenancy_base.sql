CREATE TABLE tenants (
                         id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                         name VARCHAR(150) NOT NULL,
                         status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                         created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                         updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                         CONSTRAINT chk_tenants_status
                             CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

CREATE TABLE app_users (
                           id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                           tenant_id UUID NOT NULL,
                           full_name VARCHAR(150) NOT NULL,
                           email VARCHAR(254) NOT NULL,
                           password_hash VARCHAR(255) NOT NULL,
                           created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                           updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                           CONSTRAINT fk_app_users_tenant
                               FOREIGN KEY (tenant_id)
                                   REFERENCES tenants (id),

                           CONSTRAINT uq_app_users_id_tenant
                               UNIQUE (id, tenant_id)
);

CREATE UNIQUE INDEX uq_app_users_tenant_email
    ON app_users (tenant_id, LOWER(email));

CREATE INDEX idx_app_users_tenant_id
    ON app_users (tenant_id);

ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenants FORCE ROW LEVEL SECURITY;

ALTER TABLE app_users ENABLE ROW LEVEL SECURITY;
ALTER TABLE app_users FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_tenants
    ON tenants
    USING (
        id = NULLIF(
            current_setting('app.tenant_id', true),
            ''
        )::UUID
    )
    WITH CHECK (
        id = NULLIF(
            current_setting('app.tenant_id', true),
            ''
        )::UUID
    );

CREATE POLICY tenant_isolation_app_users
    ON app_users
    USING (
        tenant_id = NULLIF(
            current_setting('app.tenant_id', true),
            ''
        )::UUID
    )
    WITH CHECK (
        tenant_id = NULLIF(
            current_setting('app.tenant_id', true),
            ''
        )::UUID
    );