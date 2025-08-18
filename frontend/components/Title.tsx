import { StyleSheet, Text, StyleProp, TextStyle  } from "react-native";

export default function Title({ children, style }: { children: React.ReactNode, style?: StyleProp<TextStyle> }) {
  return <Text style={[styles.title, style]}>{children}</Text>;
}

const styles = StyleSheet.create({
    title: {
      fontSize: 36,
      lineHeight: 40,
      fontWeight: "bold",
      letterSpacing: 1.5,
      marginBottom: 20,
    },
});