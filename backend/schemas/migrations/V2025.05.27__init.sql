create table user_credentials
(
    user_id       text        not null,
    password_hash text        not null,
    created_at    timestamptz not null,
    updated_at    timestamptz not null,
    primary key (user_id)
);

create table user_details
(
    user_id               text        not null,
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
    action_attempt_id   text        not null,
    user_id             text        not null,
    action_attempt_type text        not null,
    attempts            int         not null,
    created_at          timestamptz not null,
    updated_at          timestamptz not null,
    primary key (action_attempt_id),
    unique (user_id, action_attempt_type)
);

create table user_otp
(
    otp_id     text        not null,
    user_id    text        not null,
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
    token_id   text        not null,
    user_id    text        not null,
    token_type text        not null,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    primary key (token_id)
);

create index idx_user_token_user_id on user_token using hash (user_id);

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
