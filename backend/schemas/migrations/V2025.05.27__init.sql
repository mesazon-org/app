create table users_details
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

create table users_contacts
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
    constraint user_details_fk foreign key (user_id) references users_details (user_id)
);

create index idx_users_contacts_user_id on users_contacts (user_id);

create table waha_users
(
    user_id              text        not null,
    waha_user_id         text        not null,
    waha_user_account_id text        not null,
    waha_chat_id         text        not null,
    phone_number         text        not null,
    created_at           timestamptz not null,
    updated_at           timestamptz not null,
    primary key (user_id)
);

create table waha_user_activity
(
    user_id                    text        not null,
    is_waiting_assistant_reply boolean     not null,
    last_update                timestamptz not null,
    primary key (user_id),
    foreign key (user_id) references waha_users (user_id)
);

create table waha_user_messages
(
    user_id      text        not null,
    message_id   text        not null,
    message      text        not null,
    is_assistant boolean     not null,
    created_at   timestamptz not null,
    primary key (user_id, message_id),
    foreign key (user_id) references waha_users (user_id)
);
