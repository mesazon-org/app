FROM flyway/flyway:12.0.0-alpine
WORKDIR /flyway
COPY ./migrations /flyway/sql
CMD ["sh", "-c", "flyway clean && flyway migrate"]
