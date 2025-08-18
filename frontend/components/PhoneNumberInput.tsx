import React, { useState } from 'react';
import {
  View,
  Text,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  Modal,
  FlatList,
  Image,
  ActivityIndicator,
} from 'react-native';
import { useTranslation } from 'react-i18next';
import { countryCodes, CountryCode } from "@/constants"
import Icon from 'react-native-vector-icons/FontAwesome';

interface PhoneNumberInputProps {
  value: string;
  onChangeText: (text: string) => void;
  onBlur?: () => void;
  placeholder?: string;
  style?: any;
  error?: boolean;
  selectedCountryCode?: string;
  onCountryCodeChange?: (countryCode: string) => void;
  onVerify?: () => void;
  isVerifying?: boolean;
}

export default function PhoneNumberInput({
  value,
  onChangeText,
  onBlur,
  placeholder,
  style,
  error,
  selectedCountryCode = '+357',
  onCountryCodeChange,
  onVerify,
  isVerifying = false,
}: PhoneNumberInputProps) {
  const { t } = useTranslation();
  const [showCountryPicker, setShowCountryPicker] = useState(false);
  
  const selectedCountry = countryCodes.find(country => country.dialCode === selectedCountryCode) || countryCodes[0];

  const handleCountrySelect = (country: CountryCode) => {
    onCountryCodeChange?.(country.dialCode);
    setShowCountryPicker(false);
  };

  const handleVerify = () => {
    if (onVerify && value.trim().length > 0) {
      onVerify();
    }
  };

  const renderCountryItem = ({ item }: { item: CountryCode }) => (
    <TouchableOpacity
      style={styles.countryItem}
      onPress={() => handleCountrySelect(item)}
    >
      <Text style={styles.countryFlag}>{item.flag}</Text>
      <View style={styles.countryInfo}>
        <Text style={styles.countryName}>{item.name}</Text>
        <Text style={styles.countryDialCode}>{item.dialCode}</Text>
      </View>
    </TouchableOpacity>
  );

  return (
    <View style={styles.container}>
      <View style={[styles.inputContainer, style, error && styles.inputError]}>
        <TouchableOpacity
          style={styles.countryCodeButton}
          onPress={() => setShowCountryPicker(true)}
        >
          <Text style={styles.countryFlag}>{selectedCountry.flag}</Text>
          <Text style={styles.countryCode}>{selectedCountry.dialCode}</Text>
        </TouchableOpacity>
        
        <View style={styles.separator} />
        
        <TextInput
          value={value}
          onChangeText={onChangeText}
          onBlur={onBlur}
          placeholder={placeholder || "00 000000"}
          style={styles.phoneInput}
          keyboardType="phone-pad"
          autoCorrect={false}
        />

        {onVerify && (
          <TouchableOpacity
            style={[
              styles.verifyButton,
              (!value.trim() || isVerifying) && styles.verifyButtonDisabled
            ]}
            onPress={handleVerify}
            disabled={!value.trim() || isVerifying}
            activeOpacity={0.7}
          >
              {!isVerifying && <Icon name="whatsapp" size={20} color="#25D366" />}
            {isVerifying ? <ActivityIndicator size="small" color="#ffffff" /> : <Text style={styles.verifyText}>{t("verify")}</Text>}
          </TouchableOpacity>
        )}
      </View>

      <Modal
        visible={showCountryPicker}
        animationType="slide"
        presentationStyle="pageSheet"
      >
        <View style={styles.modalContainer}>
          <View style={styles.modalHeader}>
            <Text style={styles.modalTitle}>{t("select-country")}</Text>
            <TouchableOpacity
              onPress={() => setShowCountryPicker(false)}
              style={styles.closeButton}
            >
              <Text style={styles.closeButtonText}>{t("close")}</Text>
            </TouchableOpacity>
          </View>
          
          <FlatList
            data={countryCodes}
            renderItem={renderCountryItem}
            keyExtractor={(item) => item.code}
            style={styles.countryList}
          />
        </View>
      </Modal>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    width: '100%',
  },
  inputContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#6DBE45',
    borderRadius: 8,
    backgroundColor: '#FFFFFF',
    padding: 0,
  },
  inputError: {
    borderColor: '#FF4444',
  },
  countryCodeButton: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 12,
    paddingVertical: 12,
    minWidth: 80,
  },
  countryFlag: {
    fontSize: 20,
    marginRight: 8,
  },
  countryCode: {
    fontSize: 16,
    fontWeight: '500',
    color: '#333',
  },
  separator: {
    width: 1,
    height: 24,
    backgroundColor: '#E0E0E0',
    marginHorizontal: 8,
  },
  phoneInput: {
    flex: 1,
    paddingVertical: 12,
    paddingHorizontal: 12,
    fontSize: 16,
    color: '#333',
    borderWidth: 0,
    backgroundColor: 'transparent',
  },
  modalContainer: {
    flex: 1,
    backgroundColor: '#FFFFFF',
  },
  modalHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    padding: 16,
    borderBottomWidth: 1,
    borderBottomColor: '#E0E0E0',
  },
  modalTitle: {
    fontSize: 18,
    fontWeight: 'bold',
    color: '#333',
  },
  closeButton: {
    padding: 8,
  },
  closeButtonText: {
    fontSize: 16,
    color: '#6DBE45',
    fontWeight: '500',
  },
  countryList: {
    flex: 1,
  },
  countryItem: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: 16,
    borderBottomWidth: 1,
    borderBottomColor: '#F0F0F0',
  },
  countryInfo: {
    flex: 1,
    marginLeft: 12,
  },
  countryName: {
    fontSize: 16,
    color: '#333',
    fontWeight: '500',
  },
  countryDialCode: {
    fontSize: 14,
    color: '#666',
    marginTop: 2,
  },
  verifyButton: {
    backgroundColor: '#000000',
    borderRadius: 4,
    paddingVertical: 0,
    paddingHorizontal: 12,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    minWidth: 40,
    minHeight: 30,
    marginRight: 8,
    gap: 2
  },
  verifyButtonDisabled: {
    backgroundColor: '#000000',
    opacity: 0.7,
  },
  whatsappIcon: {
    fontSize: 20,
  },  
  verifyText: {
    fontSize: 14,
    color: '#ffffff',
    marginLeft: 4,
  },
}); 