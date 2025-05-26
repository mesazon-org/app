import { Redirect } from "expo-router";
import { View, Text } from "react-native";
import useAuth from "../hooks/useAuth";

const Dashboard = () => {
    const { user } = useAuth();


    if (!user) {
        return <Redirect href="/login" />
    }

    return <View  style={{ flex: 1 }}><Text>Welcome back {user.email}!</Text></View>
}

export default Dashboard;