import {
  View,
  Text,
  TouchableOpacity,
  StyleSheet,
  Image,
  Alert,
  Linking,
} from "react-native";
import Layout from "../Layout";
import useContacts from "@/hooks/useContacts";
import { useEffect, useState } from "react";
import { RootStackParamList, SCREEN_NAMES } from "@/services/navigation";
import { useNavigation } from "@react-navigation/native";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import Spacer from "@/components/Spacer";

type CreateUserDetailsScreenNavigationProp = NativeStackNavigationProp<
  RootStackParamList,
  typeof SCREEN_NAMES.SELECT_CONTACTS
>;

export default function ContactsRequestPermission() {
  const { hasPermission, requestPermission } = useContacts();
  const navigation = useNavigation<CreateUserDetailsScreenNavigationProp>();
  const [permissionStatus, setPermissionStatus] = useState<
    "unknown" | "denied" | "undetermined"
  >("unknown");

  useEffect(() => {
    checkPermissionStatus();
  }, []);

  const checkPermissionStatus = async () => {
    try {
      const hasPermissionResult = await hasPermission();
      if (hasPermissionResult) {
        navigation.navigate(SCREEN_NAMES.SELECT_CONTACTS);
      } else {
        setPermissionStatus("undetermined");
      }
    } catch (error) {
      console.error("Error checking permission status:", error);
      setPermissionStatus("undetermined");
    }
  };

  const handleAllow = async () => {
    try {
      const granted = await requestPermission();
      if (granted) {
        navigation.navigate(SCREEN_NAMES.SELECT_CONTACTS);
      } else {
        setPermissionStatus("denied");
      }
    } catch (error) {
      console.error("Permission request failed:", error);
      setPermissionStatus("denied");
    }
  };

  const handleDontAllow = () => {
    setPermissionStatus("denied");
  };

  // Show different UI based on status
  if (permissionStatus === "denied") {
    return (
      <Layout>
        <Text style={styles.title}>Contacts Access Required</Text>
        <Text style={styles.subtitle}>
          To continue, please enable contacts access in your device settings.
        </Text>
        
        <Spacer />

        <TouchableOpacity
          style={styles.allowButton}
          onPress={() => Linking.openSettings()}
        >
          <Text style={styles.allowButtonText}>Open Settings</Text>
        </TouchableOpacity>
      </Layout>
    );
  }

  return (
    <Layout>
      <Text style={styles.title}>Access Your Contacts</Text>

      <View style={styles.illustrationContainer}>
        <Image source={require("@/assets/access-contacts.png")} />
      </View>

      <Spacer />

      <View style={styles.buttonContainer}>
        <TouchableOpacity style={styles.allowButton} onPress={handleAllow}>
          <Text style={styles.allowButtonText}>Allow</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={styles.dontAllowButton}
          onPress={handleDontAllow}
        >
          <Text style={styles.dontAllowText}>Don't Allow</Text>
        </TouchableOpacity>
      </View>
    </Layout>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    alignItems: "center",
    justifyContent: "center",
    paddingHorizontal: 24,
  },
  illustrationContainer: {
    display: "flex",
    alignItems: "center",
    justifyContent: "center",
    marginBottom: 40,
  },
  title: {
    fontSize: 28,
    fontWeight: "bold",
    color: "#333333",
    textAlign: "center",
    marginBottom: 40,
  },
  buttonContainer: {
    width: "100%",
    alignItems: "center",
    gap: 16,
  },  
  allowButton: {
    backgroundColor: "#6DBE45",
    paddingVertical: 16,
    paddingHorizontal: 48,
    borderRadius: 12,
    width: "100%",
    alignItems: "center",
  },
  allowButtonText: {
    color: "#FFFFFF",
    fontSize: 18,
    fontWeight: "bold",
  },
  dontAllowButton: {
    paddingVertical: 12,
    paddingHorizontal: 24,
  },
  dontAllowText: {
    color: "#666666",
    fontSize: 16,
    fontWeight: "500",
  },
  subtitle: {
    fontSize: 16,
    color: "#666666",
    textAlign: "center",
    marginBottom: 30,
  },
});
