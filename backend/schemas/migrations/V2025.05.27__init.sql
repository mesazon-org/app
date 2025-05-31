CREATE TABLE rikkos.user_metadata (
    "user_id" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "first_name" TEXT NOT NULL,
    "last_name" TEXT NOT NULL,
    "country_code" TEXT NOT NULL,
    "phone_number" TEXT NOT NULL,
    "address" TEXT NOT NULL,
    "organization" TEXT NOT NULL,
    "created_at" TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    "updated_at" TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT "member_pk" PRIMARY KEY("member_id")
    UNIQUE("email")
);
