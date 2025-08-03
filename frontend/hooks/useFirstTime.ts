import AsyncStorage from "@react-native-async-storage/async-storage";
import { useEffect, useState } from "react";

export default function useFirstTime() {    
    const [isFirstTime, setIsFirstTime] = useState(true);

    useEffect(() => {
        const checkFirstTime = async () => {
            const value = await AsyncStorage.getItem('firstTime');
            if (!value) {
                setIsFirstTime(false);
            }
        }
        checkFirstTime();

        return () => {
            AsyncStorage.setItem('firstTime', 'false');
        }
    }, []);

    return {
        isFirstTime
    }
}