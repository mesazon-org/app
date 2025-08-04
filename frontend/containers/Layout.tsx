import React, { ReactNode } from "react";
import {
  View,
  StyleSheet,
  SafeAreaView,
  KeyboardAvoidingView,
  Platform,
  ScrollView,
} from "react-native";

interface LayoutProps {
  children: ReactNode;
  style?: any;
  contentContainerStyle?: any;
  scrollViewContentStyle?: any;
  showsVerticalScrollIndicator?: boolean;
  keyboardShouldPersistTaps?: "handled" | "always" | "never";
  paddingHorizontal?: number;
  paddingVertical?: number;
}

export default function Layout({
  children,
  style,
  contentContainerStyle,
  scrollViewContentStyle,
  showsVerticalScrollIndicator = false,
  keyboardShouldPersistTaps = "handled",
  paddingHorizontal = 35,
  paddingVertical = 20,
}: LayoutProps) {
  return (
    <SafeAreaView style={[styles.container, style]}>
      <KeyboardAvoidingView 
        behavior={Platform.OS === "ios" ? "padding" : "height"}
        style={styles.keyboardAvoidingView}
      >
        <ScrollView 
          style={styles.scrollView}
          contentContainerStyle={[
            styles.scrollViewContent,
            scrollViewContentStyle
          ]}
          showsVerticalScrollIndicator={showsVerticalScrollIndicator}
          keyboardShouldPersistTaps={keyboardShouldPersistTaps}
        >
          <View style={[
            styles.content,
            { paddingHorizontal, paddingVertical },
            contentContainerStyle
          ]}>
            {children}
          </View>
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: "#FFFFFF",
  },
  keyboardAvoidingView: {
    flex: 1,
  },
  scrollView: {
    flex: 1,
  },
  scrollViewContent: {
    flexGrow: 1,
    paddingBottom: 20,
  },
  content: {
    flex: 1,
  },
});