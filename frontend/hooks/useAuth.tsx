import { useState, useEffect } from "react";
import { supabase } from '@/lib/supabase';
import { Session, AuthError } from '@supabase/supabase-js';
import { User, SignUpParams } from "@/types";


export default function useAuth() {
    const [isLoading, setLoading] = useState(false);
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
            console.log('user auth state changed');
            console.log('session', session === null ? "Yes" : "No");
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

    const signIn = async (email: string, password: string) => {
        try {
            setLoading(true);
            const { data, error } = await supabase.auth.signInWithPassword({
                email,
                password,
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
            return { data: null, error };
        } finally {
            setLoading(false);
        }
    };

    const signUp = async ({ email, password, name }: SignUpParams) => {
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

    const signOut = async () => {
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


    return {
        user,
        session,
        isLoading,
        needsOnboarding,
        signIn,
        signOut,
        signUp
    };
}