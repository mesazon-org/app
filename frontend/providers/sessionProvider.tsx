import useAuth from "@/hooks/useAuth";
import { Session, SignUpParams, User } from "@/types";
import { createContext, useContext } from "react";

interface SessionContextType {
    session: Session | null;
    user: User | null;
    isLoading: boolean;
    needsOnboarding: boolean;
    signIn: (email: string, password: string) => Promise<any>;
    signOut: () => Promise<any>;
    signUp: ({email, password, name}: SignUpParams) => Promise<any>;
}

const SessionContext = createContext<SessionContextType | null>(null);

interface SessionProviderProps {
    children: React.ReactNode;
}
const SessionProvider = ({ children }: SessionProviderProps) => {
    const auth = useAuth();

    return <SessionContext.Provider value={{ ...auth }}>{children}</SessionContext.Provider>
}

export default SessionProvider;

export const useSession = () => {
    const context = useContext(SessionContext);

    if (!context) {
        throw new Error('useSession must be used within a SessionProvider');
    }

    return context;
}