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

type SignInScreenNavigationProp = NativeStackNavigationProp<
  RootStackParamList,
  typeof SCREEN_NAMES.SIGN_IN
>;

export default function ForgotPassword() {
  const { t } = useTranslation();
  const navigation = useNavigation<SignInScreenNavigationProp>();
  const {
    control,
    handleSubmit,
    formState: { errors },
  } = useForm({ defaultValues: { email: "" } });

  const onSubmit = (data: any) => {
    console.log(data);
    navigation.navigate(SCREEN_NAMES.CHECK_EMAIL);
  };

  return (
    <Layout>
      <Spacer style={{ flex: 0.5 }} />
      <Title style={{ textAlign: "center" }}>{t("forgot-password")}</Title>
      <Subtitle style={{ textAlign: "center" }}>
        {t("forgot-password-subtitle")}
      </Subtitle>

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
      />

      <FormButton onPress={handleSubmit(onSubmit)} disabled={false}>
        {t("recover-password")}
      </FormButton>

      <Spacer />

      <View style={{ flexDirection: "row", justifyContent: "center" , gap: 2}}>
        <Text style={{ textAlign: "center" }}>
          {t("remember-password")}
        </Text>
        <TouchableOpacity
          onPress={() => navigation.navigate(SCREEN_NAMES.SIGN_IN)}
        >
          <Text style={{ fontWeight: "bold" }}>{t("sign-in")}</Text>
        </TouchableOpacity>
      </View>
    </Layout>
  );
}
