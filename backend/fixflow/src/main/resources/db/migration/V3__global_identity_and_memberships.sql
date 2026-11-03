
CREATE TABLE users (
                       id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                       full_name VARCHAR(150) NOT NULL,
                       email VARCHAR(254) NOT NULL,
                       password_hash VARCHAR(255) NOT NULL,
                       status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                       created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                       updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                       CONSTRAINT chk_users_status
                           CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

CREATE UNIQUE INDEX uq_users_email
    ON users (LOWER(email));

CREATE TABLE tenant_memberships (
                                    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                                    user_id UUID NOT NULL,
                                    tenant_id UUID NOT NULL,
                                    role VARCHAR(30) NOT NULL,
                                    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
                                    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                    CONSTRAINT fk_membership_user
                                        FOREIGN KEY (user_id) REFERENCES users (id),

                                    CONSTRAINT fk_membership_tenant
                                        FOREIGN KEY (tenant_id) REFERENCES tenants (id),

                                    CONSTRAINT uq_membership_user_tenant
                                        UNIQUE (user_id, tenant_id),

                                    CONSTRAINT chk_membership_role
                                        CHECK (role IN ('OWNER', 'ADMIN', 'TECHNICIAN', 'VIEWER')),

                                    CONSTRAINT chk_membership_status
                                        CHECK (status IN ('ACTIVE', 'INVITED', 'SUSPENDED'))
);

CREATE INDEX idx_memberships_tenant
    ON tenant_memberships (tenant_id);

CREATE INDEX idx_memberships_user
    ON tenant_memberships (user_id);

CREATE UNIQUE INDEX uq_memberships_id_tenant
    ON tenant_memberships (id, tenant_id);

ALTER TABLE tenant_memberships
    ENABLE ROW LEVEL SECURITY;

ALTER TABLE tenant_memberships
    FORCE ROW LEVEL SECURITY;


CREATE POLICY tenant_isolation_memberships
ON tenant_memberships
FOR ALL
TO fixflow_runtime
USING (
    tenant_id = NULLIF(
        current_setting('app.tenant_id', true), ''
    )::UUID
)
WITH CHECK (
    tenant_id = NULLIF(
        current_setting('app.tenant_id', true), ''
    )::UUID
);

REVOKE ALL ON TABLE users FROM PUBLIC;
REVOKE ALL ON TABLE users FROM fixflow_runtime;

REVOKE ALL ON TABLE tenant_memberships
    FROM PUBLIC, fixflow_runtime;

-- Consultas aos vínculos ficam sujeitas ao RLS.
GRANT SELECT ON TABLE tenant_memberships
    TO fixflow_runtime;


