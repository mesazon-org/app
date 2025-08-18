import { View, StyleProp, ViewStyle } from "react-native";

export default function Spacer({ style }: { style?: StyleProp<ViewStyle> }) {
  return <View style={[{ flex: 1 }, style]} />;
}
