CREATE SCHEMA local_schema;

-- Flyway user
CREATE USER "flyway" WITH PASSWORD 'flyway';

GRANT CONNECT ON DATABASE "local_db" TO "flyway";
GRANT ALL ON SCHEMA "local_schema" TO "flyway";
GRANT ALL ON SCHEMA "public" TO "flyway";

-- Local user
CREATE USER "local_user" WITH PASSWORD 'local_password';

GRANT CONNECT ON DATABASE "local_db" TO "local_user";
GRANT USAGE ON SCHEMA "local_schema" TO "local_user";
ALTER DEFAULT PRIVILEGES FOR USER "flyway" IN SCHEMA "local_schema" GRANT USAGE ON SEQUENCES TO "local_user";
ALTER DEFAULT PRIVILEGES FOR USER "flyway" IN SCHEMA "local_schema" GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO "local_user";
