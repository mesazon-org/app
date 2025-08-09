import { View, Text, TouchableOpacity, StyleSheet } from "react-native";
import { Ionicons } from "@expo/vector-icons";
import { Contact } from "@/hooks/useContacts";

interface ContactsListItemProps {
    contact: Contact;
    isSelected: boolean;
    onToggle: (contact: Contact) => void;
}

export default function ContactsListItem({ contact, isSelected, onToggle }: ContactsListItemProps) {
    const phoneNumber = contact.phoneNumbers?.[0]?.number || "No phone number";
    
    return (
        <TouchableOpacity 
            style={[styles.container, isSelected ? styles.selectedContainer : styles.unselectedContainer]}
            onPress={() => onToggle(contact)}
        >
            <View style={styles.content}>
                <Text style={styles.name}>{contact.name}</Text>
                <Text style={styles.phoneNumber}>{phoneNumber}</Text>
            </View>
            <View style={[styles.checkbox, isSelected ? styles.selectedCheckbox : styles.unselectedCheckbox]}>
                {isSelected && (
                    <Ionicons name="checkmark" size={16} color="white" />
                )}
            </View>
        </TouchableOpacity>
    );
} 

const styles = StyleSheet.create({
    container: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingHorizontal: 16,
        paddingVertical: 12,
        marginVertical: 4,
        borderRadius: 8,
        backgroundColor: 'white',
        shadowColor: '#000',
        shadowOffset: {
            width: 0,
            height: 1,
        },
        shadowOpacity: 0.1,
        shadowRadius: 2,
        elevation: 2,
    },
    selectedContainer: {
        borderWidth: 2,
        borderColor: '#4CAF50',
    },
    unselectedContainer: {
        borderWidth: 1,
        borderColor: '#E0E0E0',
    },
    content: {
        flex: 1,
    },
    name: {
        fontSize: 16,
        fontWeight: 'bold',
        color: '#333333',
        marginBottom: 2,
    },
    phoneNumber: {
        fontSize: 14,
        color: '#888888',
    },
    checkbox: {
        width: 20,
        height: 20,
        borderRadius: 4,
        justifyContent: 'center',
        alignItems: 'center',
    },
    selectedCheckbox: {
        backgroundColor: '#4CAF50',
        borderWidth: 1,
        borderColor: '#4CAF50',
    },
    unselectedCheckbox: {
        backgroundColor: 'white',
        borderWidth: 1,
        borderColor: '#E0E0E0',
    },
});