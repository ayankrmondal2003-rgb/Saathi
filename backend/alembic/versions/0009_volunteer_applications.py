"""Volunteer applications and document metadata lifecycle."""
from alembic import op

revision = "0009"
down_revision = "0008"


def upgrade():
    statements = """
        CREATE TABLE volunteer_applications (
            id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
            user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE UNIQUE,
            status text NOT NULL DEFAULT 'NOT_STARTED'
                CHECK (status IN ('NOT_STARTED', 'DRAFT', 'SUBMITTED', 'UNDER_REVIEW', 'REQUIRES_CORRECTION', 'APPROVED', 'REJECTED')),
            intent text NOT NULL DEFAULT 'HELP'
                CHECK (intent IN ('HELP', 'NOT_NOW')),
            has_govt_id boolean NOT NULL DEFAULT false,
            has_address_proof boolean NOT NULL DEFAULT false,
            has_police_cert boolean NOT NULL DEFAULT false,
            has_photo boolean NOT NULL DEFAULT false,
            has_declaration boolean NOT NULL DEFAULT false,
            consent_declared boolean NOT NULL DEFAULT false,
            submitted_at timestamptz,
            reviewed_at timestamptz,
            reviewer_notes text,
            created_at timestamptz NOT NULL DEFAULT now(),
            updated_at timestamptz NOT NULL DEFAULT now()
        );
        CREATE TABLE volunteer_documents (
            id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
            application_id uuid NOT NULL REFERENCES volunteer_applications(id) ON DELETE CASCADE,
            user_id uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            doc_type text NOT NULL
                CHECK (doc_type IN ('GOVT_ID', 'ADDRESS_PROOF', 'POLICE_CERT', 'PHOTO', 'DECLARATION')),
            file_name text NOT NULL,
            file_size_bytes integer NOT NULL,
            mime_type text NOT NULL,
            storage_key text,
            uploaded_at timestamptz NOT NULL DEFAULT now(),
            UNIQUE(application_id, doc_type)
        );
        CREATE INDEX ix_volunteer_app_user ON volunteer_applications(user_id);
        CREATE INDEX ix_volunteer_app_status ON volunteer_applications(status);
    """
    for statement in statements.split(";"):
        if statement.strip():
            op.execute(statement)


def downgrade():
    op.execute("DROP TABLE volunteer_documents")
    op.execute("DROP TABLE volunteer_applications")
