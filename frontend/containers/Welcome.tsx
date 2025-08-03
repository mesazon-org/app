import { StyleSheet, View, Text } from "react-native";
import { useTranslation } from "react-i18next";

export default function WelcomePage() { 
    const { t } = useTranslation();

    return (
        <View style={styles.container}>
            <Text style={styles.title}>{t('effortlessly-manage-your-orders')}</Text>        
        </View>
    )
}

const styles = StyleSheet.create({
    container: {
        flex: 1,
        backgroundColor: '#fff',
    },
    title: {
        fontSize: 24,
        fontWeight: 'bold',
        marginBottom: 10,
        textAlign: 'center',
        color: '#333',
    }
})