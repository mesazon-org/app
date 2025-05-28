import React, { useState } from 'react'
import { StyleSheet, View } from 'react-native'
import { Button, Input } from '@rneui/themed'
import { Redirect } from 'expo-router';
import { useSession } from '@/providers/sessionProvider';


const Login = () => {
  const { user, isLoading, signIn, signUp } = useSession();
  
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState(''); 
  
  if (user) {
    return <Redirect href="/" />
  }

  return (
    <View style={styles.container}>
      <View style={[styles.verticallySpaced, styles.mt20]}>
        <Input
          label="Email"
          leftIcon={{ type: 'font-awesome', name: 'envelope' }}
          onChangeText={(text) => setEmail(text)}
          value={email}
          placeholder="email@address.com"
          autoCapitalize={'none'}
        />
      </View>
      <View style={styles.verticallySpaced}>
        <Input
          label="Password"
          leftIcon={{ type: 'font-awesome', name: 'lock' }}
          onChangeText={(text) => setPassword(text)}
          value={password}
          secureTextEntry={true}
          placeholder="Password"
          autoCapitalize={'none'}
        />
      </View>
      <View style={[styles.verticallySpaced, styles.mt20]}>
        <Button title="Sign in" disabled={isLoading} onPress={() => signIn(email, password)} />
      </View>
      <View style={styles.verticallySpaced}>
        <Button title="Sign up" disabled={isLoading} onPress={() => signUp({ email, password, name: "username" })} />
      </View>      
    </View>
  )
}

export default Login;

const styles = StyleSheet.create({
  container: {
    marginTop: 40,
    padding: 12,
  },
  verticallySpaced: {
    paddingTop: 4,
    paddingBottom: 4,
    alignSelf: 'stretch',
  },
  mt20: {
    marginTop: 20,
  },
})