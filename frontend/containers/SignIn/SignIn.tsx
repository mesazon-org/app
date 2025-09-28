import React from "react";
import {
  View,
  Text,
  Image,
  TouchableOpacity,
  StyleSheet,
} from "react-native";
import { useTranslation } from "react-i18next";
import { useForm } from "react-hook-form";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { RootStackParamList, SCREEN_NAMES } from "@/services/navigation";
import { useNavigation } from "@react-navigation/native";
import Layout from "@/containers/Layout";
import Input from "@/components/Input";
import { supabase } from "@/services/supabase";

type SignInScreenNavigationProp = NativeStackNavigationProp<
  RootStackParamList,
  typeof SCREEN_NAMES.SIGN_IN
>;
type CreateUserDetailsScreenNavigationProp = NativeStackNavigationProp<
  RootStackParamList,
  typeof SCREEN_NAMES.CREATE_USER_DETAILS
>;

interface SignInFormData {
  email: string;
  password: string;
  confirmPassword: string;
  isNewUser: boolean;
}

export default function SignIn() {
  const { t } = useTranslation();
  const navigation = useNavigation<
    SignInScreenNavigationProp & CreateUserDetailsScreenNavigationProp
  >();
  const {
    control,
    handleSubmit,
    formState: { errors, isSubmitting },
    watch,
    setValue,
  } = useForm<SignInFormData>({
    defaultValues: {
      email: "",
      password: "",
      confirmPassword: "",
      isNewUser: false,
    },
  });

  const isSigningUp = watch("isNewUser");

  const onSubmit = (data: SignInFormData) => {
    supabase.auth.signInWithPassword({
      email: data.email,
      password: data.password,
    }).then(({ data, error }) => {
      if (error) {
        console.log("Error signing in:", error);
      } else {
        console.log("Signed in:", data);
        navigation.navigate(SCREEN_NAMES.CREATE_USER_DETAILS);
      }
    });    
  };

  const onForgotPassword = () => {    
    navigation.navigate(SCREEN_NAMES.FORGOT_PASSWORD);
  };

  return (
    <Layout>
      <Image
        source={require("@/assets/Farmers.png")}
        style={styles.logo}
        resizeMode="contain"
      />

      <Text style={styles.title}>{t("sign-in")}</Text>

      <View style={styles.form}>
        <Input
          label={t("email")}
          name="email"
          control={control}
          rules={{
            required: "Email is required",
            pattern: {
              value: /^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$/i,
              message: "Invalid email address",
            },
          }}
          error={errors.email}
          keyboardType="email-address"
          autoCapitalize="none"
          autoCorrect={false}
        />

        <Input
          label={t("password")}
          name="password"
          control={control}
          rules={{
            required: "Password is required",
            minLength: {
              value: 6,
              message: "Password must be at least 6 characters",
            },
          }}
          error={errors.password}
          secureTextEntry
          autoCapitalize="none"
          autoCorrect={false}
        />

        {!isSigningUp && (
          <TouchableOpacity
            style={styles.forgotPasswordContainer}
            onPress={onForgotPassword}
          >
            <Text style={styles.forgotPasswordText}>
              {t("forgot-password")}
            </Text>
          </TouchableOpacity>
        )}

        {isSigningUp && (
          <Input
            label={t("confirm-password")}
            name="confirmPassword"
            control={control}
            rules={{
              required: "Confirm password is required",
              validate: (value: string) => {
                if (!isSigningUp) return true;
                const password = watch("password");
                return value === password || "Passwords do not match";
              },
            }}
            error={errors.confirmPassword}
            secureTextEntry
            autoCapitalize="none"
            autoCorrect={false}
          />
        )}

        <TouchableOpacity
          style={[
            styles.signInButton,
            isSubmitting && styles.signInButtonDisabled,
          ]}
          onPress={handleSubmit(onSubmit)}
          activeOpacity={0.8}
          disabled={isSubmitting}
        >
          <Text style={styles.signInButtonText}>
            {isSubmitting ? t("loading") : isSigningUp ? t("sign-up") : t("sign-in")}
          </Text>
        </TouchableOpacity>

        {isSigningUp && <>          
          <View style={styles.signUpContainer}>
            <Text style={styles.signUpText}>{t("already-have-an-account")} </Text>
            <TouchableOpacity onPress={() => setValue("isNewUser", false)}>
              <Text style={styles.signUpLink}>{t("sign-in")}</Text>
            </TouchableOpacity>
          </View>      
        </>}
        
        {!isSigningUp && <>
          <View style={styles.orContainer}>
            <View style={styles.orLine} />
            <Text style={styles.orText}>{t("or")}</Text>
            <View style={styles.orLine} />
          </View>

          <View style={styles.socialButtonsContainer}>
            <TouchableOpacity>
              <Image source={require("@/assets/Google.png")} />
            </TouchableOpacity>
            <TouchableOpacity>
              <Image source={require("@/assets/Facebook.png")} />
            </TouchableOpacity>
          </View>

          <View style={styles.signUpContainer}>
            <Text style={styles.signUpText}>{t("dont-have-an-account")} </Text>
            <TouchableOpacity onPress={() => setValue("isNewUser", true)}>
              <Text style={styles.signUpLink}>{t("sign-up")}</Text>
            </TouchableOpacity>
          </View>      
        </>}
        
      </View>
    </Layout>
  );
}

const styles = StyleSheet.create({
  logo: {
    width: 180,
    height: 180,
    alignSelf: "center",
    marginBottom: 20,
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
  forgotPasswordContainer: {
    alignItems: "flex-end",
  },
  forgotPasswordText: {
    fontSize: 14,
    color: "#6DBE45",
    fontWeight: "medium",
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
    display: "flex",
    flexDirection: "row",
    justifyContent: "center",
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
