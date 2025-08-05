import React from "react";
import {
  View,
  Text,
  Image,
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

type SignInScreenNavigationProp = NativeStackNavigationProp<RootStackParamList, typeof SCREEN_NAMES.SIGN_IN>;
type CreateUserDetailsScreenNavigationProp = NativeStackNavigationProp<RootStackParamList, typeof SCREEN_NAMES.CREATE_USER_DETAILS>;

interface SignInFormData {
  email: string;
  password: string;
}

export default function SignIn() {
  const { t } = useTranslation();
  const navigation = useNavigation<SignInScreenNavigationProp & CreateUserDetailsScreenNavigationProp>();
  const { control, handleSubmit, formState: { errors, isSubmitting } } = useForm<SignInFormData>({
    defaultValues: {
      email: "",
      password: "",
    },
  });

  const onSubmit = (data: SignInFormData) => {
    console.log("Sign in with:", data);    
    // TODO: Implement conditional signing in, if new user.. then createUserDetails otherwise dashboard
    navigation.navigate(SCREEN_NAMES.CREATE_USER_DETAILS);
  };

  const handleForgotPassword = () => {
    // TODO: Implement forgot password logic
    console.log("Forgot password");
  };

  return (
    <Layout>
      <Image
        source={require('@/assets/Farmers.png')}
        style={styles.logo}
        resizeMode="contain"
      />

      <Text style={styles.title}>{t("sign-in")}</Text>

      <View style={styles.form}>
        <View style={styles.inputContainer}>
          <Text style={styles.label}>{t("email")}</Text>
          <Controller
            control={control}
            name="email"
            rules={{
              required: "Email is required",
              pattern: {
                value: /^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$/i,
                message: "Invalid email address"
              }
            }}
            render={({ field: { onChange, onBlur, value } }) => (
              <TextInput
                value={value}
                onChangeText={onChange}
                onBlur={onBlur}
                placeholder={t("email")}
                style={[
                  styles.input,
                  errors.email && styles.inputError
                ]}
                keyboardType="email-address"
                autoCapitalize="none"
                autoCorrect={false}
              />
            )}
          />
          {errors.email && (
            <Text style={styles.errorText}>{errors.email.message}</Text>
          )}
        </View>

        <View style={styles.inputContainer}>
          <Text style={styles.label}>{t("password")}</Text>
          <Controller
            control={control}
            name="password"
            rules={{
              required: "Password is required",
              minLength: {
                value: 8,
                message: "Password must be at least 8 characters"
              }
            }}
            render={({ field: { onChange, onBlur, value } }) => (
              <TextInput
                value={value}
                onChangeText={onChange}
                onBlur={onBlur}
                placeholder={t("password")}
                style={[
                  styles.input,
                  errors.password && styles.inputError
                ]}
                secureTextEntry
                autoCapitalize="none"
                autoCorrect={false}
              />
            )}
          />
          {errors.password && (
            <Text style={styles.errorText}>{errors.password.message}</Text>
          )}
          <TouchableOpacity
            style={styles.forgotPasswordContainer}
            onPress={handleForgotPassword}
          >
            <Text style={styles.forgotPasswordText}>
              {t("forgot-password")}
            </Text>
          </TouchableOpacity>
        </View>

        <TouchableOpacity
          style={[
            styles.signInButton,
            isSubmitting && styles.signInButtonDisabled
          ]}
          onPress={handleSubmit(onSubmit)}
          activeOpacity={0.8}
          disabled={isSubmitting}
        >
          <Text style={styles.signInButtonText}>
            {isSubmitting ? "Signing in..." : t("sign-in")}
          </Text>
        </TouchableOpacity>

        <View style={styles.orContainer}>
          <View style={styles.orLine} />
          <Text style={styles.orText}>Or</Text>
          <View style={styles.orLine} />
        </View>

        <View style={styles.socialButtonsContainer}>
          <TouchableOpacity>
            <Image source={require('@/assets/Google.png')} />
          </TouchableOpacity>
          <TouchableOpacity>
            <Image source={require('@/assets/Facebook.png')} />
          </TouchableOpacity>
        </View>

        <View style={styles.signUpContainer}>
          <Text style={styles.signUpText}>
            Don't have an account?{" "}
            <Text style={styles.signUpLink}>Sign Up</Text>
          </Text>
        </View>
      </View>
    </Layout>
  );
}

const styles = StyleSheet.create({
  logo: {
    width: 180,
    height: 180,
    alignSelf: "center",
    marginBottom: 20
  },
  title: {
    fontSize: 46,
    fontWeight: "bold",
    letterSpacing: 1,
    marginBottom: 30,
  },
  form: {
    gap: 16,
  },
  inputContainer: {
    gap: 8,
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
  forgotPasswordContainer: {
    alignItems: "flex-end",
  },
  forgotPasswordText: {
    fontSize: 14,
    color: "#6DBE45",
    fontWeight: 'medium',
  },
  signInButton: {
    backgroundColor: "#6DBE45",
    alignItems: "center",
    justifyContent: "center",
    paddingVertical: 16,
    borderRadius: 8,
    marginTop: 20,
  },
  signInButtonDisabled: {
    backgroundColor: "#CCCCCC",
  },
  signInButtonText: {
    color: "#FFFFFF",
    fontWeight: "bold",
    fontSize: 16,
  },
  orContainer: {
    flexDirection: "row",
    alignItems: "center",
    marginVertical: 20,
  },
  orLine: {
    flex: 1,
    height: 1,
    backgroundColor: "#6DBE45",
  },
  orText: {
    marginHorizontal: 15,
    fontSize: 16,
    fontWeight: "bold",
    color: "#000",
  },
  socialButtonsContainer: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "center",
    gap: 24,
    marginBottom: 20,
  },
  socialButton: {
    flex: 1,
    backgroundColor: "#FFFFFF",
    borderWidth: 1,
    borderColor: "#6DBE45",
    borderRadius: 8,
    paddingVertical: 12,
    alignItems: "center",
    justifyContent: "center",
  },
  socialButtonText: {
    color: "#6DBE45",
    fontWeight: "500",
    fontSize: 14,
  },
  signUpContainer: {
    alignItems: "center",
    marginTop: 10,
  },
  signUpText: {
    fontSize: 14,
    color: "#666",
  },
  signUpLink: {
    fontWeight: "bold",
    color: "#000000",
  },
});
