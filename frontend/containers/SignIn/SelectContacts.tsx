import Header from "@/components/Header";
import Layout from "../Layout";
import useContacts, { Contact } from "@/hooks/useContacts";
import { FlatList } from "react-native";
import ContactsListItem from "./ContactsListItem";
import { useState } from "react";
import Title from "@/components/Title";
import { useTranslation } from "react-i18next";
import FormButton from "@/components/FormButton";

export default function SelectContacts() {
    const { contacts } = useContacts();
    const { t } = useTranslation();

    const [selectedContacts, setSelectedContacts] = useState<Contact[]>([]);

    const handleToggleContact = (contact: Contact) => {
        setSelectedContacts(prev => [...prev, contact]);
    };

    const handleRemoveContact = (contact: Contact) => {
        setSelectedContacts(prev => prev.filter(c => c.id !== contact.id));
    };

  return (
    <Layout>
      <Header currentStep={2} totalSteps={3} showBackButton={true} />

      <Title>{t("select-contacts")}</Title>

      <FlatList
        data={contacts}
        renderItem={({ item }) => <ContactsListItem contact={item} isSelected={selectedContacts.includes(item)} onToggle={() => selectedContacts.includes(item) ? handleRemoveContact(item) : handleToggleContact(item)}  />}
        keyExtractor={(item) => item.id}
        showsVerticalScrollIndicator={false}
      />
      
      <FormButton onPress={() => {}} disabled={false}>
        {t("continue")}
      </FormButton>

    </Layout>
  );
}