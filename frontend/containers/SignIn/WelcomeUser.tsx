import Title from "@/components/Title";
import Layout from "../Layout";
import { useTranslation } from "react-i18next";
import useUser from "@/providers/User/useUser";
import Spacer from "@/components/Spacer";
import FormButton from "@/components/FormButton";
import Subtitle from "@/components/Subtitle";
import { View } from "react-native";
import { Text } from "react-native";

export default function WelcomeUser() {
  const { user } = useUser();
  const { t } = useTranslation();

  return (
    <Layout>

      <Title style={{ textAlign: "center" }}>{t("welcome-user", { label: user?.name })}</Title>

      <Spacer />

      <View style={{ display: "flex", height: '50%', width: '100%',  marginBottom: 16, flexDirection: "row", alignItems: "center", justifyContent: "center", backgroundColor: "gray", opacity: 0.5 }}>        
      </View>
      <Subtitle style={{ textAlign: "center" }}>{t("youve-successfully-created-your-account")}</Subtitle>
      
      <Spacer />

      <FormButton style={{ width: "100%" }} onPress={() => {}} disabled={false}>
        {t("start-managing-orders")}
      </FormButton>
    </Layout>
  );
}