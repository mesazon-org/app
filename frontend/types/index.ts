interface Session {
    access_token: string;
    refresh_token: string;
}

interface User {
    id: string;
    email: string;
    name: string;
}

interface SignUpParams {
    email: string;
    password: string;
    name: string;
}

export type { Session, User, SignUpParams };