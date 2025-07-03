CREATE TABLE users_details (
    user_id TEXT PRIMARY KEY NOT NULL,
    email TEXT NOT NULL UNIQUE,
    first_name TEXT NOT NULL,
    last_name TEXT NOT NULL,
    phone_region TEXT NOT NULL,
    phone_national_number TEXT NOT NULL,
    address_line_1 TEXT NOT NULL,
    address_line_2 TEXT,
    city TEXT NOT NULL,
    postal_code TEXT NOT NULL,
    company TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE users_contacts (
    contact_id TEXT PRIMARY KEY NOT NULL,
    user_id TEXT NOT NULL,
    display_name TEXT NOT NULL,
    first_name TEXT NOT NULL,
    last_name TEXT,
    company TEXT,
    email TEXT,
    phone_region TEXT NOT NULL,
    phone_national_number TEXT NOT NULL,
    address_line_1 TEXT,
    address_line_2 TEXT,
    city TEXT,
    postal_code TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT user_fk FOREIGN KEY(user_id) REFERENCES users_details(user_id)
);

CREATE INDEX idx_users_contacts_user_id ON users_contacts(user_id);
