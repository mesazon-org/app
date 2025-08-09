import { useEffect, useState } from "react";
import * as Contacts from "expo-contacts";
import type { Contact as ExpoContact } from "expo-contacts";

type ContactData = {
  id: string;
  name: string;
  phoneNumbers: { id: string; number: string; label: string; }[] | undefined;
};

// Export for compatibility with existing code
export type Contact = ContactData;

// Mock contacts for development/testing
const mockContacts: ContactData[] = [
  { id: "1", name: "John Lendon", phoneNumbers: [{ id: "1", number: "+357 99 657832", label: "mobile" }] },
  { id: "2", name: "Ann Emerson", phoneNumbers: [{ id: "2", number: "+357 99 123456", label: "mobile" }] },
  { id: "3", name: "Andy Domson", phoneNumbers: [{ id: "3", number: "+357 99 789012", label: "mobile" }] },
  { id: "4", name: "Eda Grinson", phoneNumbers: [{ id: "4", number: "+357 99 345678", label: "mobile" }] },
  { id: "5", name: "Sarah Johnson", phoneNumbers: [{ id: "5", number: "+357 99 901234", label: "mobile" }] },
  { id: "6", name: "Michael Chen", phoneNumbers: [{ id: "6", number: "+357 99 567890", label: "mobile" }] },
  { id: "7", name: "Emma Wilson", phoneNumbers: [{ id: "7", number: "+357 99 234567", label: "mobile" }] },
  { id: "8", name: "David Brown", phoneNumbers: [{ id: "8", number: "+357 99 678901", label: "mobile" }] },
  { id: "9", name: "Lisa Garcia", phoneNumbers: [{ id: "9", number: "+357 99 012345", label: "mobile" }] },
  { id: "10", name: "Robert Taylor", phoneNumbers: [{ id: "10", number: "+357 99 456789", label: "mobile" }] },
  { id: "11", name: "Maria Rodriguez", phoneNumbers: [{ id: "11", number: "+357 99 890123", label: "mobile" }] },
  { id: "12", name: "James Anderson", phoneNumbers: [{ id: "12", number: "+357 99 234567", label: "mobile" }] },
  { id: "13", name: "Jennifer Martinez", phoneNumbers: [{ id: "13", number: "+357 99 678901", label: "mobile" }] },
  { id: "14", name: "Christopher Lee", phoneNumbers: [{ id: "14", number: "+357 99 012345", label: "mobile" }] },
  { id: "15", name: "Amanda White", phoneNumbers: [{ id: "15", number: "+357 99 456789", label: "mobile" }] },
  { id: "16", name: "Daniel Thompson", phoneNumbers: [{ id: "16", number: "+357 99 890123", label: "mobile" }] },
  { id: "17", name: "Nicole Davis", phoneNumbers: [{ id: "17", number: "+357 99 234567", label: "mobile" }] },
  { id: "18", name: "Kevin Miller", phoneNumbers: [{ id: "18", number: "+357 99 678901", label: "mobile" }] },
  { id: "19", name: "Rachel Garcia", phoneNumbers: [{ id: "19", number: "+357 99 012345", label: "mobile" }] },
  { id: "20", name: "Steven Johnson", phoneNumbers: [{ id: "20", number: "+357 99 456789", label: "mobile" }] },
  { id: "21", name: "Michelle Williams", phoneNumbers: [{ id: "21", number: "+357 99 890123", label: "mobile" }] },
  { id: "22", name: "Thomas Jones", phoneNumbers: [{ id: "22", number: "+357 99 234567", label: "mobile" }] },
  { id: "23", name: "Jessica Brown", phoneNumbers: [{ id: "23", number: "+357 99 678901", label: "mobile" }] },
  { id: "24", name: "Ryan Davis", phoneNumbers: [{ id: "24", number: "+357 99 012345", label: "mobile" }] },
  { id: "25", name: "Stephanie Wilson", phoneNumbers: [{ id: "25", number: "+357 99 456789", label: "mobile" }] },
];

export default function useContacts() {
  const [contacts, setContacts] = useState<ContactData[]>([]);

  useEffect(() => {
    // For development, use mock data instead of real contacts
    setContacts(mockContacts);
    // Uncomment the line below to use real contacts instead
    // getContacts().then(setContacts);
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
