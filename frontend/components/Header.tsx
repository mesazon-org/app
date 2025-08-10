import React from "react";
import {
  View,
  Text,
  TouchableOpacity,
  StyleSheet,
} from "react-native";
import { useNavigation } from "@react-navigation/native";

interface HeaderProps {
  currentStep?: number;
  totalSteps?: number;
  showBackButton?: boolean;
  onBackPress?: () => void;
  progressPercentage?: number;
}

export default function Header({
  currentStep = 1,
  totalSteps = 1,
  showBackButton = true,
  onBackPress,
  progressPercentage,
}: HeaderProps) {
  const navigation = useNavigation();

  const handleBackPress = () => {
    if (onBackPress) {
      onBackPress();
    } else {
      navigation.goBack();
    }
  };

  const progress = progressPercentage ?? (currentStep / totalSteps) * 100;

  return (
    <View style={styles.container}>
      <View style={styles.header}>
        {showBackButton && (
          <TouchableOpacity
            style={styles.backButton}
            onPress={handleBackPress}
            activeOpacity={0.7}
          >
            <Text style={styles.backIcon}>‹</Text>
          </TouchableOpacity>
        )}
        
        <View style={styles.progressContainer}>
          <View style={styles.progressBar}>
            <View 
              style={[
                styles.progressFill, 
                { width: `${progress}%` }
              ]} 
            />
          </View>
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    marginBottom: 20,
  },
  header: {
    display: 'flex',
    flexDirection: "row",
    alignItems: "center",
    height: 56,
    paddingRight: 40
  },
  backButton: {
    width: 56,
    display: 'flex',
    alignItems: "flex-start"
  },
  backIcon: {
    fontSize: 46,
    fontWeight: "300",
    color: "#000"
  },
  progressContainer: {
    flex: 1,
    justifyContent: "center",
    marginLeft: 16,
    marginTop: 9
  },
  progressBar: {
    height: 8,
    backgroundColor: "#E5E5E5",
    borderRadius: 4,
    overflow: "hidden",
  },
  progressFill: {
    height: "100%",
    backgroundColor: "#6DBE45",
    borderRadius: 4,
  }
}); 