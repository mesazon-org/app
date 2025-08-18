import React from "react";
import { View, Text, TextInput, StyleSheet, TextInputProps } from "react-native";
import { Control, Controller, FieldError } from "react-hook-form";

interface InputProps extends Omit<TextInputProps, 'onChangeText' | 'onBlur' | 'value'> {
  label: string;
  name: string;
  control: Control<any>;
  rules?: any;
  error?: FieldError;
  placeholder?: string;
  autoCapitalize?: "none" | "sentences" | "words" | "characters";
  autoCorrect?: boolean;
  secureTextEntry?: boolean;
  keyboardType?: "default" | "email-address" | "numeric" | "phone-pad" | "number-pad";
}

export default function Input({
  label,
  name,
  control,
  rules,
  error,
  placeholder,
  autoCapitalize = "none",
  autoCorrect = false,
  secureTextEntry = false,
  keyboardType = "default",
  ...props
}: InputProps) {
  return (
    <View style={styles.inputContainer}>
      <Text style={styles.label}>{label}</Text>
      <Controller
        control={control}
        name={name}
        rules={rules}
        render={({ field: { onChange, onBlur, value } }) => (
          <TextInput
            value={value}
            onChangeText={onChange}
            onBlur={onBlur}
            placeholder={placeholder || label}
            style={[styles.input, error && styles.inputError]}
            autoCapitalize={autoCapitalize}
            autoCorrect={autoCorrect}
            secureTextEntry={secureTextEntry}
            keyboardType={keyboardType}
            {...props}
          />
        )}
      />
      <View style={styles.errorContainer}>
        {error && (
          <Text style={styles.errorText}>{error.message}</Text>
        )}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  inputContainer: {
    gap: 6,
  },
  label: {
    fontWeight: "500",
    color: "#333",
  },
  input: {
    borderWidth: 1,
    borderColor: "#6DBE45",
    padding: 12,
    borderRadius: 8,
    fontSize: 16,
    backgroundColor: "#FFFFFF",
  },
  inputError: {
    borderColor: "#FF4444",
  },
  errorContainer: {
    minHeight: 2,
  },
  errorText: {
    color: "#FF4444",
    fontSize: 12,
    marginTop: 2,
  },
}); 