import AsyncStorage from "@react-native-async-storage/async-storage";
import { useState } from "react";

import { useEffect } from "react";

interface User {
    id: string;
    email: string;
    name: string;
}
export default function useAuth() {
    const [loading, setLoading] = useState(false);
    const [needsOnboarding, setNeedsOnboarding] = useState(false);
    const [user, setUser] = useState<User | null>(null);

    useEffect(() => {
        (async () => {
            const user = await AsyncStorage.getItem('user');
            setUser(user ? JSON.parse(user) : null);
        })();
    }, []);

    const logout = () => {
        AsyncStorage.removeItem('user');
        setUser(null);
    }

    const updateUser = (user: User) => {
        setUser(user);
        AsyncStorage.setItem('user', JSON.stringify(user));
    }


    return {
        user,
        loading,
        needsOnboarding,
        logout,
        updateUser
    }
}