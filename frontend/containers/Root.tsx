import { StatusBar } from 'expo-status-bar';
import { StyleSheet, Text, View, ScrollView } from 'react-native';
import { useTranslation } from 'react-i18next';
import '../i18n';
import LanguageSwitcher from '../components/LanguageSwitcher';

export default function Root() {
  const { t } = useTranslation();

  return (
    <View style={styles.container}>
      <ScrollView contentContainerStyle={styles.scrollContainer}>
        <Text style={styles.title}>{t('welcome')}</Text>
        <Text style={styles.subtitle}>{t('startWorking')}</Text>
        
        <LanguageSwitcher />
        
        <View style={styles.infoContainer}>
          <Text style={styles.infoText}>
            Current language: {t('language')}
          </Text>
        </View>
      </ScrollView>
      <StatusBar style="auto" />
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#fff',
  },
  scrollContainer: {
    flexGrow: 1,
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 20,
  },
  title: {
    fontSize: 24,
    fontWeight: 'bold',
    marginBottom: 10,
    textAlign: 'center',
    color: '#333',
  },
  subtitle: {
    fontSize: 16,
    marginBottom: 30,
    textAlign: 'center',
    color: '#666',
    paddingHorizontal: 20,
  },
  infoContainer: {
    marginTop: 20,
    padding: 15,
    backgroundColor: '#f0f0f0',
    borderRadius: 8,
  },
  infoText: {
    fontSize: 14,
    color: '#555',
    textAlign: 'center',
  },
});
