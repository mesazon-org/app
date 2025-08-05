import { View, Text } from "react-native";
import Layout from "../Layout";
import useContacts from "@/hooks/useContacts";
import { useEffect } from "react";
// import Header from "@/components/Header";

export default function ContactsRequestPermission() {
  const { contacts, hasPermission, requestPermission, getContacts, searchContacts, getContactById } = useContacts();

  useEffect(() => {
    hasPermission().then(console.log);
  }, []);

  return (
    <Layout>
      {/* <Header currentStep={1} totalSteps={3} showBackButton={true} /> */}

      <Text>ContactsRequestPermission</Text>
    </Layout>
  );
}
