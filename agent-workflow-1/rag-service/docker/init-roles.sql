-- Development-only credentials. Production provisioning must supply separate secrets.
CREATE ROLE rag_api LOGIN PASSWORD 'rag_dev_only' NOSUPERUSER NOBYPASSRLS;
CREATE ROLE rag_worker LOGIN PASSWORD 'rag_dev_only' NOSUPERUSER NOBYPASSRLS;
CREATE ROLE rag_dispatcher LOGIN PASSWORD 'rag_dev_only' NOSUPERUSER NOBYPASSRLS;

