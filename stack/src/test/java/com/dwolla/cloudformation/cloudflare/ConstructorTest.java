package com.dwolla.cloudformation.cloudflare;

import com.dwolla.lambda.cloudflare.CloudflareHandler;

public class ConstructorTest {

    // This needs to compile for the Lambda to be constructable at AWS
    final CloudflareHandler handler = new CloudflareHandler();

}
