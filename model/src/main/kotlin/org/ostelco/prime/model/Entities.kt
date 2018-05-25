package org.ostelco.prime.model

import com.fasterxml.jackson.annotation.JsonIgnore

interface Entity {
    var id: String
}

data class Offer(
        @JsonIgnore
        override var id: String = "",
        var segments: List<Segment> = emptyList(),
        var products: List<Product> = emptyList()) : Entity

data class Segment(
        @JsonIgnore
        override var id: String = "",
        var subscribers: List<Subscriber> = emptyList()) : Entity

data class Subscriber(
        var email: String = "",
        var name: String = "",
        var address: String = "",
        var postCode: String = "",
        var city: String = "",
        var country: String = "") : Entity {

    constructor(email: String) : this()

    override var id: String
        @JsonIgnore
        get() = email
        @JsonIgnore
        set(value) {
            email = value
        }
}

data class Price(
        var amount: Int = 0,
        var currency: String = "")

data class Product(
        var sku: String = "",
        var price: Price = Price(0, ""),
        @JsonIgnore
        var properties: Map<String, String> = mapOf(),
        @JsonIgnore
        var presentation: Map<String, String> = mapOf()) : Entity {

    override var id: String
        @JsonIgnore
        get() = sku
        @JsonIgnore
        set(value) {
            sku = value
        }
}

data class ProductClass(
        override var id: String = "",
        var properties: List<String> = listOf()) : Entity

data class PurchaseRecord(
    var msisdn: String = "",
    var sku: String = "",
    var millisSinceEpoch: Long = 0L)

class Subscription(var msisdn: String)