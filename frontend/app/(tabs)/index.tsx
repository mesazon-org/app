import React from 'react';
import useAuth from '@/hooks/useAuth';
import { Redirect } from 'expo-router';
import { ActivityIndicator, Platform, SafeAreaView, ScrollView, StyleSheet, Text, View } from 'react-native';


export default function HomeScreen() {
  const { user, loading, needsOnboarding } = useAuth();


  if (loading) {
    return (
      <ActivityIndicator />
    );
  }
  
  if (needsOnboarding) {
    return <Redirect href="/onboarding" />
  }
  
  if (!user) {
    return <Redirect href="/login" />
  }

  return (
    <SafeAreaView style={styles.safeArea}>
      <ScrollView contentContainerStyle={styles.scrollContainer}>
        <View style={styles.container}>
        <Text>Hello {user.name}</Text>          
                             
        </View>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  stepContainer: {
    marginTop: 24,
    padding: 16,
    backgroundColor: '#fff',
    borderRadius: 16,
    shadowColor: '#000',
    shadowOpacity: 0.06,
    shadowRadius: 8,
    shadowOffset: { width: 0, height: 2 },
  },
  sectionTitle: {
    fontSize: 18,
    fontWeight: 'bold',
    marginBottom: 12,
    color: '#222',
  },
  emptyText: {
    color: '#888',
    fontStyle: 'italic',
    textAlign: 'center',
    marginVertical: 12,
  },
  hunterCard: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#f8fafc',
    borderRadius: 12,
    padding: 12,
    marginBottom: 10,
    shadowColor: '#16a34a',
    shadowOpacity: 0.04,
    shadowRadius: 4,
    shadowOffset: { width: 0, height: 2 },
  },
  avatarIcon: {
    marginRight: 14,
  },
  hunterInfo: {
    flex: 1,
  },
  hunterProfile: {
    fontSize: 24,
    marginRight: 14,
  },
  hunterName: {
    fontSize: 16,
    fontWeight: '600',
    color: '#222',
    marginBottom: 2,
  },
  hunterLocation: {
    color: '#555',
    fontSize: 14,
  },
  map: {
    width: '100%',
    height: '100%',
  },
  safeArea: {
    flex: 1,
    backgroundColor: '#f8fafc',
    paddingTop: Platform.OS === 'android' ? 32 : 0,
  },
  scrollContainer: {
    flexGrow: 1,
    paddingBottom: 100, // Space for tab bar
  },
  container: {
    padding: 16,
  },
  mapContainer: {
    height: 300,
    width: '100%',
    marginVertical: 10,
    borderRadius: 10,
    overflow: 'hidden',
  },
});