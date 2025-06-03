CREATE TABLE "user_metadata" (
    "user_id" TEXT PRIMARY KEY NOT NULL,
    "email" TEXT NOT NULL UNIQUE,
    "first_name" TEXT NOT NULL,
    "last_name" TEXT NOT NULL,
    "country_code" TEXT NOT NULL,
    "phone_number" TEXT NOT NULL,
    "address_line_1" TEXT NOT NULL,
    "address_line_2" TEXT,
    "city" TEXT NOT NULL,
    "postal_code" TEXT NOT NULL,
    "company" TEXT NOT NULL,
    "created_at" TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    "updated_at" TIMESTAMP WITHOUT TIME ZONE NOT NULL
);
