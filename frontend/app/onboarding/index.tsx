import useAuth from '@/hooks/useAuth';
import { useRouter } from 'expo-router';
import React, { useState } from 'react';
import { KeyboardAvoidingView, Platform, ScrollView, StyleSheet, Switch, Text, TextInput, TouchableOpacity, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

const AVATARS = ['🦌', '🏹', '🎯', '🐻'];

const GAME_OPTIONS = ['Deer', 'Elk', 'Turkey', 'Waterfowl', 'Bear', 'Other'];
const WEAPON_OPTIONS = ['Rifle', 'Bow', 'Muzzleloader', 'Shotgun', 'Other'];
const EXPERIENCE_OPTIONS = ['Beginner', 'Intermediate', 'Expert'];

export default function OnboardingScreen() {
  const { user, updateUser, logout } = useAuth();
  const router = useRouter();

  const [displayName, setDisplayName] = useState('');
  const [username, setUsername] = useState('');
  const [bio, setBio] = useState('');
  const [avatarIndex, setAvatarIndex] = useState(0);
  const [experience, setExperience] = useState(EXPERIENCE_OPTIONS[0]);
  const [preferredGame, setPreferredGame] = useState<string[]>([]);
  const [favoriteWeapon, setFavoriteWeapon] = useState(WEAPON_OPTIONS[0]);
  const [yearsExperience, setYearsExperience] = useState('');
  const [region, setRegion] = useState('');
  const [isVisible, setIsVisible] = useState(true);

  const toggleGame = (game: string) => {
    setPreferredGame(prev =>
      prev.includes(game) ? prev.filter(g => g !== game) : [...prev, game]
    );
  };

  const handleSubmit = async () => {
    if (!user) return;
   
  };

  return (
    <SafeAreaView style={styles.safeArea}>
      <KeyboardAvoidingView
        style={{ flex: 1 }}
        behavior={Platform.OS === "ios" ? "padding" : undefined}
      >
        <ScrollView contentContainerStyle={styles.container}>
          <Text style={styles.title}>Set Up Your Hunter Profile</Text>

          <Text style={styles.label}>Profile Picture</Text>
          <View style={styles.avatarRow}>
            {AVATARS.map((avatar, idx) => (
              <TouchableOpacity
                key={idx}
                onPress={() => setAvatarIndex(idx)}
                style={[
                  styles.avatarContainer,
                  avatarIndex === idx && styles.avatarSelected,
                ]}
              >
                <Text style={styles.avatarEmoji}>{avatar}</Text>
              </TouchableOpacity>
            ))}
          </View>

          <Text style={styles.label}>Display Name</Text>
          <TextInput
            style={styles.input}
            placeholder="e.g. Hunter John"
            value={displayName}
            onChangeText={setDisplayName}
          />          

          <Text style={styles.label}>Experience Level</Text>
          <View style={styles.row}>
            {EXPERIENCE_OPTIONS.map(option => (
              <TouchableOpacity
                key={option}
                style={[
                  styles.chip,
                  experience === option && styles.chipSelected,
                ]}
                onPress={() => setExperience(option)}
              >
                <Text style={experience === option && styles.chipTextSelected}>{option}</Text>
              </TouchableOpacity>
            ))}
          </View>

          <Text style={styles.label}>Preferred Game</Text>
          <View style={styles.row}>
            {GAME_OPTIONS.map(option => (
              <TouchableOpacity
                key={option}
                style={[
                  styles.chip,
                  preferredGame.includes(option) && styles.chipSelected,
                ]}
                onPress={() => toggleGame(option)}
              >
                <Text style={preferredGame.includes(option) && styles.chipTextSelected}>{option}</Text>
              </TouchableOpacity>
            ))}
          </View>

          <Text style={styles.label}>Favorite Weapon</Text>
          <View style={styles.row}>
            {WEAPON_OPTIONS.map(option => (
              <TouchableOpacity
                key={option}
                style={[
                  styles.chip,
                  favoriteWeapon === option && styles.chipSelected,
                ]}
                onPress={() => setFavoriteWeapon(option)}
              >
                <Text style={favoriteWeapon === option && styles.chipTextSelected}>{option}</Text>
              </TouchableOpacity>
            ))}
          </View>

          <Text style={styles.label}>Years of Experience</Text>
          <TextInput
            style={styles.input}
            placeholder="e.g. 5"
            value={yearsExperience}
            onChangeText={setYearsExperience}
            keyboardType="numeric"
          />

          <Text style={styles.label}>Region</Text>
          <TextInput
            style={styles.input}
            placeholder="e.g. Colorado"
            value={region}
            onChangeText={setRegion}
          />

          <Text style={styles.label}>Bio</Text>
          <TextInput
            style={[styles.input, { height: 60 }]}
            placeholder="Tell us about your hunting style..."
            value={bio}
            onChangeText={setBio}
            multiline
          />

          <View style={styles.visibilityRow}>
            <Text style={styles.label}>Show me on the map</Text>
            <Switch value={isVisible} onValueChange={setIsVisible} />
          </View>

          <TouchableOpacity style={styles.button} onPress={handleSubmit}>
            <Text style={styles.buttonText}>Complete Onboarding</Text>
          </TouchableOpacity>

          <TouchableOpacity style={styles.logoutButton} onPress={logout}>
            <Text style={styles.buttonText}>Logout</Text>
          </TouchableOpacity>
        </ScrollView>
        </KeyboardAvoidingView>
        </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: '#f8fafc', // subtle off-white
    paddingTop: Platform.OS === 'android' ? 32 : 0, // extra for Android status bar
  },
  container: {
    padding: 24,
    alignItems: 'stretch',
    backgroundColor: '#fff',
  },
  title: {
    fontSize: 24,
    marginBottom: 18,
    textAlign: 'center',
    fontWeight: 'bold',
  },
  label: {
    marginTop: 16,
    marginBottom: 6,
    fontWeight: '600',
  },
  avatarRow: {
    flexDirection: 'row',
    justifyContent: 'center',
    marginBottom: 8,
  },
  avatarContainer: {
    marginHorizontal: 8,
    borderWidth: 2,
    borderColor: 'transparent',
    borderRadius: 40,
    padding: 2,
  },
  avatarSelected: {
    borderColor: '#007AFF',
  },
  avatarEmoji: {
    fontSize: 48,
    textAlign: 'center',
  },
  row: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    marginBottom: 8,
  },
  chip: {
    backgroundColor: '#eee',
    borderRadius: 16,
    paddingHorizontal: 14,
    paddingVertical: 8,
    marginRight: 8,
    marginBottom: 8,
  },
  chipSelected: {
    backgroundColor: '#007AFF',
  },
  chipTextSelected: {
    color: '#fff',
    fontWeight: 'bold',
  },
  input: {
    borderWidth: 1,
    borderColor: '#ddd',
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 8,
    backgroundColor: '#fafafa',
    marginBottom: 4,
  },
  visibilityRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: 16,
    marginBottom: 24,
    justifyContent: 'space-between',
  },
  button: {
    backgroundColor: '#007AFF',
    borderRadius: 8,
    paddingVertical: 14,
    alignItems: 'center',
    marginTop: 10,
    marginBottom: 10,
  },
  logoutButton: {
    backgroundColor: '#000',
    borderRadius: 8,
    paddingVertical: 14,
    alignItems: 'center',
    marginTop: 0,
    marginBottom: 30,
  },
  buttonText: {
    color: '#fff',
    fontWeight: 'bold',
    fontSize: 16,
  },
});