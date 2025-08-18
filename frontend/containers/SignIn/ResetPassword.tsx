import Title from "@/components/Title";
import Layout from "../Layout";
import Subtitle from "@/components/Subtitle";
import { useTranslation } from "react-i18next";
import FormButton from "@/components/FormButton";
import Input from "@/components/Input";
import { useForm } from "react-hook-form";
import Spacer from "@/components/Spacer";
import { Text, TouchableOpacity, View } from "react-native";
import { RootStackParamList, SCREEN_NAMES } from "@/services/navigation";
import { useNavigation } from "@react-navigation/native";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";

type ResetPasswordScreenNavigationProp = NativeStackNavigationProp<
  RootStackParamList,
  typeof SCREEN_NAMES.RESET_PASSWORD
>;

export default function ResetPassword() {
  const { t } = useTranslation();
  const navigation = useNavigation<ResetPasswordScreenNavigationProp>();
  const {
    control,
    handleSubmit,
    formState: { errors, isSubmitting },
    watch,
  } = useForm({
    defaultValues: {
      newPassword: "",
      confirmPassword: "",
    },
  });

  const onSubmit = (data: any) => {
    console.log("Reset password:", data);
    // TODO: Implement password reset logic
    // navigation.navigate(SCREEN_NAMES.PASSWORD_RESET_SUCCESS);
  };

  const handleSignIn = () => {
    navigation.navigate(SCREEN_NAMES.SIGN_IN);
  };

  return (
    <Layout>
      <Spacer style={{ flex: 0.5 }} />
      
      <Title style={{ textAlign: "center" }}>{t("reset-password")}</Title>
      <Subtitle style={{ textAlign: "center" }}>
        {t("reset-password-subtitle")}
      </Subtitle>

      <Input
        label={t("new-password")}
        name="newPassword"
        control={control}
        rules={{
          required: t("new-password-required"),
          minLength: {
            value: 8,
            message: t("password-min-length"),
          },
        }}
        error={errors.newPassword}
        placeholder={t("new-password")}
        secureTextEntry
        autoCapitalize="none"
        autoCorrect={false}
      />

      <Input
        label={t("confirm-new-password")}
        name="confirmPassword"
        control={control}
        rules={{
          required: t("confirm-password-required"),
          validate: (value: string) => {
            const newPassword = watch("newPassword");
            return value === newPassword || t("passwords-do-not-match");
          },
        }}
        error={errors.confirmPassword}
        placeholder={t("confirm-new-password")}
        secureTextEntry
        autoCapitalize="none"
        autoCorrect={false}
      />

      <FormButton onPress={handleSubmit(onSubmit)} disabled={isSubmitting}>
        {isSubmitting ? t("resetting") : t("reset-password")}
      </FormButton>

      <Spacer />

      <View style={{ flexDirection: "row", justifyContent: "center", gap: 2 }}>
        <Text style={{ textAlign: "center", color: "#666666" }}>
          {t("remember-password")}
        </Text>
        <TouchableOpacity onPress={handleSignIn}>
          <Text style={{ fontWeight: "bold", color: "#333333" }}>
            {t("sign-in")}
          </Text>
        </TouchableOpacity>
      </View>
    </Layout>
  );
}