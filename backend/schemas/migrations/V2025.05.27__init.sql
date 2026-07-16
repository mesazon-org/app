create table user_credentials
(
    user_id       uuid        not null,
    password_hash text        not null,
    created_at    timestamptz not null,
    updated_at    timestamptz not null,
    primary key (user_id)
);

create table user_details
(
    user_id               uuid        not null,
    email                 text        not null,
    full_name             text,
    phone_region          text,
    phone_country_code    text,
    phone_national_number text,
    phone_number_e164     text,
    onboard_stage         text        not null,
    created_at            timestamptz not null,
    updated_at            timestamptz not null,
    primary key (user_id),
    unique (email)
);

create table user_action_attempt
(
    action_attempt_id   uuid        not null,
    user_id             uuid        not null,
    action_attempt_type text        not null,
    attempts            int         not null,
    created_at          timestamptz not null,
    updated_at          timestamptz not null,
    primary key (action_attempt_id),
    unique (user_id, action_attempt_type)
);

create table user_otp
(
    otp_id     uuid        not null,
    user_id    uuid        not null,
    otp        text        not null,
    otp_type   text        not null,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    expires_at timestamptz not null,
    primary key (otp_id),
    unique (user_id, otp_type)
);

create table user_token
(
    token_id   uuid        not null,
    user_id    uuid        not null,
    token_type text        not null,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    primary key (token_id)
);

create index idx_user_token_user_id on user_token using hash (user_id);

create table organization_details
(
    organization_id            uuid        not null,
    name                       text        not null,
    slug                       text        not null,
    phone_region               text        not null,
    phone_country_code         text        not null,
    phone_national_number      text        not null,
    phone_number_e164          text        not null,
    email                      text        not null,
    organization_stage         text        not null,
    address_line_1             text        not null,
    address_line_2             text,
    city                       text        not null,
    postal_code                text        not null,
    country                    text        not null,
    logo_original_bucket_key   text,
    logo_normalized_bucket_key text,
    logo_original_file_name    text,
    created_at                 timestamptz not null,
    updated_at                 timestamptz not null,
    primary key (organization_id),
    unique (slug)
);

create table organization_user
(
    organization_id uuid        not null,
    user_id         uuid        not null,
    user_role       text        not null,
    created_at      timestamptz not null,
    updated_at      timestamptz not null,
    primary key (organization_id, user_id)
);

create table customer
(
    organization_id uuid        not null,
    customer_id     uuid        not null,
    customer_type   text        not null,
    status          text        not null,
    created_at      timestamptz not null,
    updated_at      timestamptz not null,
    primary key (organization_id, customer_id)
);

create table customer_business_details
(
    organization_id       uuid        not null,
    customer_id           uuid        not null,
    business_name         text        not null,
    email                 text,
    phone_region          text,
    phone_country_code    text,
    phone_national_number text,
    phone_number_e164     text,
    tax_id                text,
    address_line_1        text,
    address_line_2        text,
    city                  text,
    postal_code           text,
    country               text,
    created_at            timestamptz not null,
    updated_at            timestamptz not null,
    primary key (organization_id, customer_id),
    unique (organization_id, business_name),
    foreign key (organization_id, customer_id)
        references customer (organization_id, customer_id)
);

create table customer_individual_details
(
    organization_id       uuid        not null,
    customer_id           uuid        not null,
    full_name             text        not null,
    email                 text,
    phone_region          text,
    phone_country_code    text,
    phone_national_number text,
    phone_number_e164     text,
    address_line_1        text,
    address_line_2        text,
    city                  text,
    postal_code           text,
    country               text,
    created_at            timestamptz not null,
    updated_at            timestamptz not null,
    primary key (organization_id, customer_id),
    unique (organization_id, full_name),
    foreign key (organization_id, customer_id)
        references customer (organization_id, customer_id)
);

create table customer_business_contact
(
    organization_id              uuid        not null,
    customer_id                  uuid        not null,
    customer_business_contact_id uuid        not null,
    full_name                    text        not null,
    role                         text,
    email                        text,
    phone_region                 text,
    phone_country_code           text,
    phone_national_number        text,
    phone_number_e164            text,
    created_at                   timestamptz not null,
    updated_at                   timestamptz not null,
    primary key (organization_id, customer_id, customer_business_contact_id),
    foreign key (organization_id, customer_id)
        references customer_business_details (organization_id, customer_id)
);


create table waha_user
(
    user_id              text        not null,
    full_name            text        not null,
    waha_user_id         text        not null,
    waha_user_account_id text        not null,
    waha_chat_id         text        not null,
    phone_number         text        not null,
    created_at           timestamptz not null,
    updated_at           timestamptz not null,
    unique (waha_user_id),
    primary key (user_id)
);

CREATE INDEX idx_waha_user_waha_id ON waha_user (waha_user_id);

create table waha_user_activity
(
    user_id                    text        not null,
    last_message_id            text,
    is_waiting_assistant_reply boolean     not null,
    last_update                timestamptz not null,
    primary key (user_id),
    foreign key (user_id) references waha_user (user_id)
);

create table waha_user_message
(
    user_id      text        not null,
    message_id   text        not null,
    message      text        not null,
    is_assistant boolean     not null,
    created_at   timestamptz not null,
    primary key (user_id, message_id),
    foreign key (user_id) references waha_user (user_id)
);

CREATE INDEX idx_waha_user_message_order ON waha_user_message (user_id, created_at DESC);
