import { StyleSheet, Text, StyleProp, TextStyle  } from "react-native";

export default function Subtitle({ children, style }: { children: React.ReactNode, style?: StyleProp<TextStyle> }) {
  return <Text style={[styles.subtitle, style]}>{children}</Text>;
}

const styles = StyleSheet.create({
    subtitle: {   
      fontSize: 16,
      fontWeight: "normal",
      letterSpacing: 1,
      marginBottom: 20,
      color: "#666666",
    },
});