create table user_onboard
(
    user_id       text        not null,
    email         text        not null,
    full_name     text,
    phone_number  text,
    password_hash text,
    stage         text        not null,
    created_at    timestamptz not null,
    updated_at    timestamptz not null,
    primary key (user_id),
    unique (email)
);

create index idx_user_onboard_email on user_onboard (email);

create table user_otp
(
    otp_id     text        not null,
    user_id    text        not null,
    otp        text        not null,
    otp_type   text        not null,
    created_at timestamptz not null,
    expires_at timestamptz not null,
    primary key (otp_id),
);

create table user_details
(
    user_id        text        not null,
    email          text        not null,
    first_name     text        not null,
    last_name      text        not null,
    phone_number   text        not null,
    address_line_1 text        not null,
    address_line_2 text,
    city           text        not null,
    postal_code    text        not null,
    company        text        not null,
    created_at     timestamptz not null,
    updated_at     timestamptz not null,
    primary key (user_id),
    unique (email)
);

create table user_contact
(
    user_contact_id text        not null,
    user_id         text        not null,
    display_name    text        not null,
    first_name      text        not null,
    phone_number    text        not null,
    last_name       text,
    company         text,
    email           text,
    address_line_1  text,
    address_line_2  text,
    city            text,
    postal_code     text,
    created_at      timestamptz not null,
    updated_at      timestamptz not null,
    primary key (user_contact_id),
    unique (phone_number, user_id),
    constraint user_details_fk foreign key (user_id) references user_details (user_id)
);

create index idx_user_contact_user_id on user_contact (user_id);

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
