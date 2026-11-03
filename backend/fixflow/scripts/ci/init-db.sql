-- Exclusivo para o PostgreSQL descartável do CI. Nunca executar em produção.
CREATE ROLE fixflow_runtime LOGIN PASSWORD 'ci_runtime' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE ROLE fixflow_migrator LOGIN PASSWORD 'ci_migrator' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
CREATE DATABASE fixflow OWNER fixflow_migrator;
CREATE DATABASE fixflow_test OWNER fixflow_migrator;

\connect fixflow
REVOKE ALL ON DATABASE fixflow FROM PUBLIC;
GRANT CONNECT ON DATABASE fixflow TO fixflow_runtime;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO fixflow_runtime;

\connect fixflow_test
REVOKE ALL ON DATABASE fixflow_test FROM PUBLIC;
GRANT CONNECT ON DATABASE fixflow_test TO fixflow_runtime;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO fixflow_runtime;
