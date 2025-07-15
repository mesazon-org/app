CREATE TABLE users_details (
    user_id TEXT NOT NULL,
    email TEXT NOT NULL,
    first_name TEXT NOT NULL,
    last_name TEXT NOT NULL,
    phone_number TEXT NOT NULL,
    address_line_1 TEXT NOT NULL,
    address_line_2 TEXT,
    city TEXT NOT NULL,
    postal_code TEXT NOT NULL,
    company TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(user_id),
    UNIQUE(email)
);

CREATE TABLE users_contacts (
    user_contact_id TEXT NOT NULL,
    user_id TEXT NOT NULL,
    display_name TEXT NOT NULL,
    first_name TEXT NOT NULL,
    phone_number TEXT NOT NULL,
    last_name TEXT,
    company TEXT,
    email TEXT,
    address_line_1 TEXT,
    address_line_2 TEXT,
    city TEXT,
    postal_code TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(user_contact_id),
    UNIQUE(phone_number, user_id),
    CONSTRAINT user_details_fk FOREIGN KEY(user_id) REFERENCES users_details(user_id)
);

CREATE INDEX idx_users_contacts_user_id ON users_contacts(user_id);
