import React, { useState } from "react";
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  StyleSheet,
} from "react-native";
import { useTranslation } from "react-i18next";
import { useForm, Controller } from "react-hook-form";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { RootStackParamList, SCREEN_NAMES } from "@/services/navigation";
import { useNavigation } from "@react-navigation/native";
import Layout from "@/containers/Layout";
import Header from "@/components/Header";
import PhoneNumberInput from "@/components/PhoneNumberInput";

type CreateUserDetailsScreenNavigationProp = NativeStackNavigationProp<RootStackParamList, typeof SCREEN_NAMES.CREATE_USER_DETAILS>;

interface CreateUserDetailsFormData {
  firstName: string;
  lastName: string;
  phoneNumber: string;
  countryCode: string;
  addressLine1: string;
  addressLine2: string;
  city: string;
  postalCode: string;
  company: string;
}

export default function CreateUserDetails() {
  const { t } = useTranslation();
  const navigation = useNavigation<CreateUserDetailsScreenNavigationProp>();
  const [selectedCountryCode, setSelectedCountryCode] = useState("+357");
  
  const { control, handleSubmit, formState: { errors, isSubmitting } } = useForm<CreateUserDetailsFormData>({
    defaultValues: {
      firstName: "",
      lastName: "",
      phoneNumber: "",
      countryCode: "+357",
      addressLine1: "",
      addressLine2: "",
      city: "",
      postalCode: "",
      company: "",
    },
  });

  const onSubmit = (data: CreateUserDetailsFormData) => {
    console.log('Submitting')
    // Combine country code with phone number
    const fullPhoneNumber = `${selectedCountryCode}${data.phoneNumber}`;
    const formData = {
      ...data,
      phoneNumber: fullPhoneNumber,
      countryCode: selectedCountryCode,
    };
    
    console.log("Create user details:", formData);
    // TODO: Implement user details creation
    navigation.navigate(SCREEN_NAMES.CONTACTS_REQUEST_PERMISSION);
  };

  return (
    <Layout>
      <Header
        currentStep={1}
        totalSteps={3}
        showBackButton={true}
      />

      <Text style={styles.title}>{t("set-details")}</Text>

      <View style={styles.form}>
        <View style={styles.inputContainer}>
          <Text style={styles.label}>{t("first-name")}</Text>
          <Controller
            control={control}
            name="firstName"
            rules={{
              required: t("first-name-is-required"),
              minLength: {
                value: 2,
                message: t("first-name-must-be-at-least-2-characters")
              }
            }}
            render={({ field: { onChange, onBlur, value } }) => (
              <TextInput
                value={value}
                onChangeText={onChange}
                onBlur={onBlur}
                placeholder={t("first-name")}
                style={[
                  styles.input,
                  errors.firstName && styles.inputError
                ]}
                autoCapitalize="words"
                autoCorrect={false}
              />
            )}
          />
          {errors.firstName && (
            <Text style={styles.errorText}>{errors.firstName.message}</Text>
          )}
        </View>

        <View style={styles.inputContainer}>
          <Text style={styles.label}>{t("last-name")}</Text>
          <Controller
            control={control}
            name="lastName"
            rules={{
              required: t("last-name-is-required"),
              minLength: {
                value: 2,
                message: t("last-name-must-be-at-least-2-characters")
              }
            }}
            render={({ field: { onChange, onBlur, value } }) => (
              <TextInput
                value={value}
                onChangeText={onChange}
                onBlur={onBlur}
                placeholder={t("last-name")}
                style={[
                  styles.input,
                  errors.lastName && styles.inputError
                ]}
                autoCapitalize="words"
                autoCorrect={false}
              />
            )}
          />
          {errors.lastName && (
            <Text style={styles.errorText}>{errors.lastName.message}</Text>
          )}
        </View>

        <View style={styles.inputContainer}>
          <Text style={styles.label}>{t("phone-number")}</Text>
          <Controller
            control={control}
            name="phoneNumber"
            rules={{
              required: t("phone-number-is-required"),
              minLength: {
                value: 6,
                message: t("phone-number-must-be-at-least-10-characters")
              }
            }}            
            render={({ field: { onChange, onBlur, value } }) => (
              <PhoneNumberInput
                value={value}
                onChangeText={onChange}
                onBlur={onBlur}
                placeholder="00 000000"                
                error={!!errors.phoneNumber}
                selectedCountryCode={selectedCountryCode}
                onCountryCodeChange={(countryCode) => {
                  setSelectedCountryCode(countryCode);
                  control._formValues.countryCode = countryCode;
                }}
              />
            )}
          />
          {errors.phoneNumber && (
            <Text style={styles.errorText}>{errors.phoneNumber.message}</Text>
          )}
        </View>

        <View style={styles.inputContainer}>
          <Text style={styles.label}>Address Line 1</Text>
          <Controller
            control={control}
            name="addressLine1"
            rules={{
              required: t("address-line-1-is-required"),
            }}
            render={({ field: { onChange, onBlur, value } }) => (
              <TextInput
                value={value}
                onChangeText={onChange}
                onBlur={onBlur}
                placeholder={t("address-line-1")}
                style={[
                  styles.input,
                  errors.addressLine1 && styles.inputError
                ]}
                autoCapitalize="words"
                autoCorrect={false}
              />
            )}
          />
          {errors.addressLine1 && (
            <Text style={styles.errorText}>{errors.addressLine1.message}</Text>
          )}
        </View>

        <View style={styles.inputContainer}>
          <Text style={styles.label}>Address Line 2</Text>
          <Controller
            control={control}
            name="addressLine2"
            render={({ field: { onChange, onBlur, value } }) => (
              <TextInput
                value={value}
                onChangeText={onChange}
                onBlur={onBlur}
                placeholder={t("address-line-2")}
                style={[
                  styles.input,
                  errors.addressLine2 && styles.inputError
                ]}
                autoCapitalize="words"
                autoCorrect={false}
              />
            )}
          />
          {errors.addressLine2 && (
            <Text style={styles.errorText}>{errors.addressLine2.message}</Text>
          )}
        </View>

        <View style={styles.rowContainer}>
          <View style={[styles.inputContainer, styles.halfWidth]}>
            <Text style={styles.label}>City</Text>
            <Controller
              control={control}
              name="city"
              rules={{
                required: t("city-is-required"),
              }}
              render={({ field: { onChange, onBlur, value } }) => (
                <TextInput
                  value={value}
                  onChangeText={onChange}
                  onBlur={onBlur}
                  placeholder={t("city")}
                  style={[
                    styles.input,
                    errors.city && styles.inputError
                  ]}
                  autoCapitalize="words"
                  autoCorrect={false}
                />
              )}
            />
            {errors.city && (
              <Text style={styles.errorText}>{errors.city.message}</Text>
            )}
          </View>

          <View style={[styles.inputContainer, styles.halfWidth]}>
            <Text style={styles.label}>Postal Code</Text>
            <Controller
              control={control}
              name="postalCode"
              rules={{
                required: t("postal-code-is-required"),
              }}
              render={({ field: { onChange, onBlur, value } }) => (
                <TextInput
                  value={value}
                  onChangeText={onChange}
                  onBlur={onBlur}
                  placeholder={t("postal-code")}
                  style={[
                    styles.input,
                    errors.postalCode && styles.inputError
                  ]}
                  autoCapitalize="characters"
                  autoCorrect={false}
                />
              )}
            />
            {errors.postalCode && (
              <Text style={styles.errorText}>{errors.postalCode.message}</Text>
            )}
          </View>
        </View>

        <View style={styles.inputContainer}>
          <Text style={styles.label}>Company</Text>
          <Controller
            control={control}
            name="company"
            render={({ field: { onChange, onBlur, value } }) => (
              <TextInput
                value={value}
                onChangeText={onChange}
                onBlur={onBlur}
                placeholder={t("company")}
                style={[
                  styles.input,
                  errors.company && styles.inputError
                ]}
                autoCapitalize="words"
                autoCorrect={false}
              />
            )}
          />
          {errors.company && (
            <Text style={styles.errorText}>{errors.company.message}</Text>
          )}
        </View>

        <TouchableOpacity
          style={[
            styles.submitButton,
            isSubmitting && styles.submitButtonDisabled
          ]}
          onPress={handleSubmit(onSubmit)}
          activeOpacity={0.8}
          disabled={isSubmitting}
        >
          <Text style={styles.submitButtonText}>
            {isSubmitting ? t("setting") : t("continue")}
          </Text>
        </TouchableOpacity>
      </View>
    </Layout>   
  );
}

const styles = StyleSheet.create({
  title: {
    fontSize: 24,
    fontWeight: "bold",
    letterSpacing: 1,
    marginBottom: 20,
  },
  subtitle: {
    fontSize: 16,
    color: "#666",
    marginBottom: 30,
  },
  form: {
    gap: 16,
  },
  inputContainer: {
    gap: 8,
  },
  rowContainer: {
    flexDirection: "row",
    gap: 16,
  },
  halfWidth: {
    flex: 1,
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
  },
  inputError: {
    borderColor: "#FF4444",
  },
  errorText: {
    color: "#FF4444",
    fontSize: 12,
    marginTop: 4,
  },
  submitButton: {
    backgroundColor: "#6DBE45",
    alignItems: "center",
    justifyContent: "center",
    paddingVertical: 16,
    borderRadius: 8,
    marginTop: 20,
  },
  submitButtonDisabled: {
    backgroundColor: "#CCCCCC",
  },
  submitButtonText: {
    color: "#FFFFFF",
    fontWeight: "bold",
    fontSize: 16,
  },
});
