import Header from "@/components/Header";
import Layout from "../Layout";
import useContacts, { Contact } from "@/hooks/useContacts";
import { FlatList, View, TextInput, StyleSheet, ScrollView } from "react-native";
import ContactsListItem from "./ContactsListItem";
import { useState, useMemo } from "react";
import Title from "@/components/Title";
import { useTranslation } from "react-i18next";
import FormButton from "@/components/FormButton";
import Spacer from "@/components/Spacer";
import { RootStackParamList, SCREEN_NAMES } from "@/services/navigation";
import { useNavigation } from "@react-navigation/native";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";

type WelcomeUserScreenNavigationProp = NativeStackNavigationProp<
  RootStackParamList,
  typeof SCREEN_NAMES.WELCOME_USER
>;

export default function SelectContacts() {
  const { contacts } = useContacts();
  const { t } = useTranslation();
  const navigation = useNavigation<WelcomeUserScreenNavigationProp>();
  const [selectedContacts, setSelectedContacts] = useState<Contact[]>([]);
  const [searchQuery, setSearchQuery] = useState("");

  const filteredContacts = useMemo(() => {
    if (!searchQuery.trim())
      return contacts.sort((a, b) => a.name.localeCompare(b.name, "en"));

    const query = searchQuery.toLowerCase();
    return contacts
      .filter(
        (contact) =>
          contact.name.toLowerCase().includes(query) ||
          contact.phoneNumbers?.some((phone) =>
            phone.number.replace(/\s/g, "").includes(query.replace(/\s/g, ""))
          )
      )
      .sort((a, b) => a.name.localeCompare(b.name, "en"));
  }, [contacts, searchQuery]);

  const handleToggleContact = (contact: Contact) => {
    setSelectedContacts((prev) => [...prev, contact]);
  };

  const handleRemoveContact = (contact: Contact) => {
    setSelectedContacts((prev) => prev.filter((c) => c.id !== contact.id));
  };

  return (
    <Layout>
      <Header currentStep={2} totalSteps={3} showBackButton={true} />

      <Title>{t("select-contacts")}</Title>

      <View style={styles.searchContainer}>
        <TextInput
          style={styles.searchInput}
          placeholder={t("search-contacts")}
          value={searchQuery}
          onChangeText={setSearchQuery}
          placeholderTextColor="#888888"
        />
      </View>

      <ScrollView style={styles.listContainer}>
        {filteredContacts.map((item) => (
          <ContactsListItem
            key={item.id}
            contact={item}
            isSelected={selectedContacts.includes(item)}
            onToggle={() =>
              selectedContacts.includes(item)
                ? handleRemoveContact(item)
                : handleToggleContact(item)
            }
          />
        ))}
      </ScrollView>

      <Spacer />

      <FormButton onPress={() => navigation.navigate(SCREEN_NAMES.WELCOME_USER)} disabled={false}>
        {t("continue")}
      </FormButton>
    </Layout>
  );
}

const styles = StyleSheet.create({
  searchContainer: {
    marginBottom: 16,
  },
  searchInput: {
    height: 48,
    borderWidth: 1,
    borderColor: "#E0E0E0",
    borderRadius: 8,
    paddingHorizontal: 16,
    fontSize: 16,
    backgroundColor: "white",
    color: "#333333",
  },
  listContainer: {
    flex: 1,
    maxHeight: 400,
  },
});
