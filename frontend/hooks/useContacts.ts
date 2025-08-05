import { useEffect, useState } from "react";
import * as Contacts from "expo-contacts";

export interface Contact {
  id: string;
  name: string;
  phoneNumbers?: Array<{
    id: string;
    number: string;
    label: string;
  }>;
  emails?: Array<{
    id: string;
    email: string;
    label: string;
  }>;
}

export default function useContacts() {
  const [contacts, setContacts] = useState<Contact[]>([]);

  useEffect(() => {
    getContacts().then(setContacts);
  }, []);

  const hasPermission = async () => {
    const { status } = await Contacts.getPermissionsAsync();
    return status === "granted";
  };

  const requestPermission = async () => {
    const { status } = await Contacts.requestPermissionsAsync();
    return status === "granted";
  };

  const getContacts = async () => {
    try {
      const hasPermissionGranted = await hasPermission();
      if (!hasPermissionGranted) {
        const granted = await requestPermission();
        if (!granted) {
          throw new Error("Contacts permission denied");
        }
      }

      const { data } = await Contacts.getContactsAsync({
        fields: [Contacts.Fields.PhoneNumbers, Contacts.Fields.Name],
      });

      if (data.length > 0) {
        return data
          .filter(
            (contact) =>
              contact.name &&
              contact.phoneNumbers &&
              contact.phoneNumbers.length > 0
          )
          .map((contact) => ({
            id: contact.id || "",
            name: contact.name || "Unknown",
            phoneNumbers: contact.phoneNumbers?.map((phone) => ({
              id: phone.id || "",
              number: phone.number || "",
              label: phone.label || "mobile",
            })),
          }));
      }

      return [];
    } catch (error) {
      console.error("Error getting contacts:", error);
      throw error;
    }
  };

  const searchContacts = async (query: string) => {
    const contacts = await getContacts();
    const lowerQuery = query.toLowerCase();

    return contacts.filter(
      (contact) =>
        contact.name.toLowerCase().includes(lowerQuery) ||
        contact.phoneNumbers?.some((phone) =>
          phone.number.replace(/\s/g, "").includes(query.replace(/\s/g, ""))
        )
    );
  };

  const getContactById = async (id: string) => {
    try {
      const { data } = await Contacts.getContactsAsync({
        fields: [Contacts.Fields.PhoneNumbers, Contacts.Fields.Name],
        id,
      });

      if (data.length > 0) {
        const contact = data[0];
        return {
          id: contact.id || "",
          name: contact.name || "Unknown",
          phoneNumbers: contact.phoneNumbers?.map((phone) => ({
            id: phone.id || "",
            number: phone.number || "",
            label: phone.label || "mobile",
          })),
        };
      }

      return null;
    } catch (error) {
      console.error("Error getting contact by ID:", error);
      throw error;
    }
  };

  return {
    contacts,
    hasPermission,
    requestPermission,
    getContacts,
    searchContacts,
    getContactById
  };
}
