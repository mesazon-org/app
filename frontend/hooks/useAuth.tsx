import { useState, useEffect } from "react";
import { supabase } from '@/lib/supabase';
import { Session, AuthError } from '@supabase/supabase-js';

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

export default function useAuth() {
    const [loading, setLoading] = useState(false);
    const [needsOnboarding, setNeedsOnboarding] = useState(false);
    const [user, setUser] = useState<User | null>(null);
    const [session, setSession] = useState<Session | null>(null);

    useEffect(() => {
        // Get initial session
        supabase.auth.getSession().then(({ data: { session } }) => {
            setSession(session);
            if (session?.user) {
                setUser({
                    id: session.user.id,
                    email: session.user.email!,
                    name: session.user.user_metadata.name || ''
                });
            }
        });

        // Listen for auth changes
        const { data: { subscription } } = supabase.auth.onAuthStateChange((_event, session) => {
            setSession(session);
            if (session?.user) {
                setUser({
                    id: session.user.id,
                    email: session.user.email!,
                    name: session.user.user_metadata.name || ''
                });
            } else {
                setUser(null);
            }
        });

        return () => subscription.unsubscribe();
    }, []);

    const login = async (email: string, password: string) => {
        try {
            setLoading(true);
            const { data, error } = await supabase.auth.signInWithPassword({
                email,
                password,
            });

            console.log('data', data);
            console.log('error', error);

            if (error) throw error;

            if (data.user) {
                setUser({
                    id: data.user.id,
                    email: data.user.email!,
                    name: data.user.user_metadata.name || ''
                });
            }

            return { data, error: null };
        } catch (error) {
            return { data: null, error };
        } finally {
            setLoading(false);
        }
    };

    const logout = async () => {
        setLoading(true);
        try {
            await supabase.auth.signOut();
            setUser(null);
            setSession(null);
        } catch (error) {
            console.error('Error logging out:', error);
        } finally {
            setLoading(false);
        }
    };

    const updateUser = async (userData: Partial<User>) => {
        try {
            setLoading(true);
            const { data, error } = await supabase.auth.updateUser({
                data: userData
            });

            if (error) throw error;

            if (data.user) {
                setUser({
                    id: data.user.id,
                    email: data.user.email!,
                    name: data.user.user_metadata.name || ''
                });
            }
        } catch (error) {
            console.error('Error updating user:', error);
        } finally {
            setLoading(false);
        }
    };

    const signup = async ({ email, password, name }: SignUpParams) => {
        try {
            setLoading(true);
            const { data, error } = await supabase.auth.signUp({
                email,
                password,
                options: {
                    data: {
                        name
                    }
                }
            });

            if (error) throw error;

            if (data.user) {
                setUser({
                    id: data.user.id,
                    email: data.user.email!,
                    name: data.user.user_metadata.name || ''
                });
            }

            return { data, error: null };
        } catch (error) {
            return { data: null, error: error as AuthError };
        } finally {
            setLoading(false);
        }
    };

    return {
        user,
        session,
        loading,
        needsOnboarding,
        login,
        logout,
        updateUser,
        signup
    };
}