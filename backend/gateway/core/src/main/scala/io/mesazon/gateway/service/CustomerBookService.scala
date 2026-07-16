package io.mesazon.gateway.service

import io.mesazon.domain.gateway.*
import io.mesazon.gateway.{smithy, HttpErrorHandler}
import zio.*

import java.util.UUID

object CustomerBookService {

  private final class CustomerBookServiceImpl() extends smithy.CustomerBookService[ServiceTask] {

    /** HTTP POST /insert/customer-individual */
    override def insertCustomerIndividualPost(
        organizationID: UUID,
        request: smithy.InsertCustomerIndividualPostRequest,
    ): ServiceTask[Unit] =
      ZIO.fail(ServiceError.InternalServerError.UnexpectedError("insertCustomerIndividualPost is not implemented yet"))

    /** HTTP POST /insert/customer-individuals */
    override def insertCustomerIndividualsPost(
        organizationID: UUID,
        request: smithy.InsertCustomerIndividualsPostRequest,
    ): ServiceTask[Unit] =
      ZIO.fail(ServiceError.InternalServerError.UnexpectedError("insertCustomerIndividualsPost is not implemented yet"))

    /** HTTP POST /insert/customer-business */
    override def insertCustomerBusinessPost(
        organizationID: UUID,
        request: smithy.InsertCustomerBusinessPostRequest,
    ): ServiceTask[Unit] =
      ZIO.fail(ServiceError.InternalServerError.UnexpectedError("insertCustomerBusinessPost is not implemented yet"))

    /** HTTP POST /insert/customer-businesses */
    override def insertCustomerBusinessesPost(
        organizationID: UUID,
        request: smithy.InsertCustomerBusinessesPostRequest,
    ): ServiceTask[Unit] =
      ZIO.fail(ServiceError.InternalServerError.UnexpectedError("insertCustomerBusinessesPost is not implemented yet"))

    /** HTTP POST /insert/customers */
    override def insertCustomersPost(
        organizationID: UUID,
        request: smithy.InsertCustomersPostRequest,
    ): ServiceTask[Unit] =
      ZIO.fail(ServiceError.InternalServerError.UnexpectedError("insertCustomersPost is not implemented yet"))

    /** HTTP PUT /update/customer-individual */
    override def updateCustomerIndividualPut(
        organizationID: UUID,
        request: smithy.UpdateCustomerIndividualPutRequest,
    ): ServiceTask[Unit] =
      ZIO.fail(ServiceError.InternalServerError.UnexpectedError("updateCustomerIndividualPut is not implemented yet"))

    /** HTTP PUT /update/customer-business */
    override def updateCustomerBusinessPut(
        organizationID: UUID,
        request: smithy.UpdateCustomerBusinessPutRequest,
    ): ServiceTask[Unit] =
      ZIO.fail(ServiceError.InternalServerError.UnexpectedError("updateCustomerBusinessPut is not implemented yet"))

    /** HTTP PUT /add/customer-business-contacts */
    override def addCustomerBusinessContactsPut(
        organizationID: UUID,
        request: smithy.AddCustomerBusinessContactsPutRequest,
    ): ServiceTask[Unit] =
      ZIO.fail(
        ServiceError.InternalServerError.UnexpectedError("addCustomerBusinessContactsPut is not implemented yet")
      )

    /** HTTP PUT /remove/customer-business-contacts */
    override def removeCustomerBusinessContactsPut(
        organizationID: UUID,
        request: smithy.RemoveCustomerBusinessContactsPutRequest,
    ): ServiceTask[Unit] =
      ZIO.fail(
        ServiceError.InternalServerError.UnexpectedError("removeCustomerBusinessContactsPut is not implemented yet")
      )

    /** HTTP GET /get/customer-individual/{customerID} */
    override def getCustomerIndividualGet(
        organizationID: UUID,
        customerID: UUID,
    ): ServiceTask[smithy.GetCustomerIndividualGetResponse] =
      ZIO.fail(ServiceError.InternalServerError.UnexpectedError("getCustomerIndividualGet is not implemented yet"))

    /** HTTP GET /get/customer-business/{customerID} */
    override def getCustomerBusinessGet(
        organizationID: UUID,
        customerID: UUID,
    ): ServiceTask[smithy.GetCustomerBusinessGetResponse] =
      ZIO.fail(ServiceError.InternalServerError.UnexpectedError("getCustomerBusinessGet is not implemented yet"))

    /** HTTP GET /get/customers */
    override def getCustomersGet(
        organizationID: UUID
    ): ServiceTask[smithy.GetCustomersGetResponse] =
      ZIO.fail(ServiceError.InternalServerError.UnexpectedError("getCustomersGet is not implemented yet"))
  }

  private def observed(
      service: smithy.CustomerBookService[ServiceTask]
  ): smithy.CustomerBookService[Task] =
    new smithy.CustomerBookService[Task] {

      /** HTTP POST /insert/customer-individual */
      override def insertCustomerIndividualPost(
          organizationID: UUID,
          request: smithy.InsertCustomerIndividualPostRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(service.insertCustomerIndividualPost(organizationID, request))

      /** HTTP POST /insert/customer-individuals */
      override def insertCustomerIndividualsPost(
          organizationID: UUID,
          request: smithy.InsertCustomerIndividualsPostRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(service.insertCustomerIndividualsPost(organizationID, request))

      /** HTTP POST /insert/customer-business */
      override def insertCustomerBusinessPost(
          organizationID: UUID,
          request: smithy.InsertCustomerBusinessPostRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(service.insertCustomerBusinessPost(organizationID, request))

      /** HTTP POST /insert/customer-businesses */
      override def insertCustomerBusinessesPost(
          organizationID: UUID,
          request: smithy.InsertCustomerBusinessesPostRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(service.insertCustomerBusinessesPost(organizationID, request))

      /** HTTP POST /insert/customers */
      override def insertCustomersPost(
          organizationID: UUID,
          request: smithy.InsertCustomersPostRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(service.insertCustomersPost(organizationID, request))

      /** HTTP PUT /update/customer-individual */
      override def updateCustomerIndividualPut(
          organizationID: UUID,
          request: smithy.UpdateCustomerIndividualPutRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(service.updateCustomerIndividualPut(organizationID, request))

      /** HTTP PUT /update/customer-business */
      override def updateCustomerBusinessPut(
          organizationID: UUID,
          request: smithy.UpdateCustomerBusinessPutRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(service.updateCustomerBusinessPut(organizationID, request))

      /** HTTP PUT /add/customer-business-contacts */
      override def addCustomerBusinessContactsPut(
          organizationID: UUID,
          request: smithy.AddCustomerBusinessContactsPutRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(service.addCustomerBusinessContactsPut(organizationID, request))

      /** HTTP PUT /remove/customer-business-contacts */
      override def removeCustomerBusinessContactsPut(
          organizationID: UUID,
          request: smithy.RemoveCustomerBusinessContactsPutRequest,
      ): Task[Unit] =
        HttpErrorHandler.errorResponseHandler(service.removeCustomerBusinessContactsPut(organizationID, request))

      /** HTTP GET /get/customer-individual/{customerID} */
      override def getCustomerIndividualGet(
          organizationID: UUID,
          customerID: UUID,
      ): Task[smithy.GetCustomerIndividualGetResponse] =
        HttpErrorHandler.errorResponseHandler(service.getCustomerIndividualGet(organizationID, customerID))

      /** HTTP GET /get/customer-business/{customerID} */
      override def getCustomerBusinessGet(
          organizationID: UUID,
          customerID: UUID,
      ): Task[smithy.GetCustomerBusinessGetResponse] =
        HttpErrorHandler.errorResponseHandler(service.getCustomerBusinessGet(organizationID, customerID))

      /** HTTP GET /get/customers */
      override def getCustomersGet(
          organizationID: UUID
      ): Task[smithy.GetCustomersGetResponse] =
        HttpErrorHandler.errorResponseHandler(service.getCustomersGet(organizationID))
    }

  val local = ZLayer.derive[CustomerBookServiceImpl].project[smithy.CustomerBookService[ServiceTask]](identity)

  val live = local >>> ZLayer.fromFunction(observed)
}
